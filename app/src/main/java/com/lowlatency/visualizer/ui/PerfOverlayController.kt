package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.animation.AnimationUtils
import android.widget.Button
import android.widget.TextSwitcher
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.VisualizerSurfaceView
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.content.edit

/**
 * Owns the on-canvas performance overlay: the giant eased FPS readout plus the
 * mono diagnostic block (CPU, RAM, audio, Link, Hue). Self-contained — it binds
 * its own views, persists its toggle, and drives its own pollers. MainActivity
 * just forwards the lifecycle and supplies the few cross-cutting values the
 * overlay reports (audio source, Hue stream stats).
 *
 */
class PerfOverlayController(
    private val activity: AppCompatActivity,
    private val glView: VisualizerSurfaceView,
    private val prefs: SharedPreferences,
    private val isSystemAudioMode: () -> Boolean,
    private val hueStats: () -> HueStats,
) {

    /** Snapshot of the Hue stream the overlay reports. [active] mirrors the old
     *  `hueController.isInitialized && isEnabled`; [packetsSent] is read even when
     *  inactive so the pps baseline seeds correctly on enable. */
    data class HueStats(
        val active: Boolean,
        val packetsSent: Long,
        val packetsFailed: Long,
        val rttMs: Long,
    )

    private lateinit var perfOverlay: View
    private lateinit var btnPerfOverlay: Button
    private lateinit var perfFpsValue: TextSwitcher
    private lateinit var perfFrameMs: TextView
    private lateinit var perfDetail: TextView

    private var displayedFps = 0f
    private var shownFps = -1          // last integer drawn (snap/hysteresis state)
    private var lastHuePackets = 0L
    private var smoothedHuePps = 0f
    private var lastHuePpsTimeNs = 0L

    var enabled = false
        private set

    /** The overlay container, so callers (e.g. the scene label) can position
     *  themselves clear of it. */
    val overlayView: View get() = perfOverlay

    private val perfHandler = Handler(Looper.getMainLooper())
    private val perfPoller = object : Runnable {
        override fun run() {
            updatePerfOverlay()
            perfHandler.postDelayed(this, 500L)
        }
    }
    // Smoothly eases the hero FPS readout toward the live value (~20 Hz) so the
    // giant number counts fluidly rather than snapping with the 500 ms data poll.
    private val perfFpsTicker = object : Runnable {
        override fun run() {
            displayedFps += (glView.rendererFps - displayedFps) * 0.22f
            renderHeroFps()
            perfHandler.postDelayed(this, 50L)
        }
    }

    /** Bind views, restore the persisted toggle, and wire the menu button. */
    fun bind() {
        btnPerfOverlay = activity.findViewById(R.id.btn_perf_overlay)
        perfOverlay = activity.findViewById(R.id.perf_overlay)
        perfFpsValue = activity.findViewById(R.id.perf_fps_value)
        perfFrameMs = activity.findViewById(R.id.perf_frame_ms)
        perfDetail = activity.findViewById(R.id.perf_detail)
        setEnabled(prefs.getBoolean(KEY_PERF_OVERLAY, false))
        btnPerfOverlay.setOnClickListener { setEnabled(!enabled) }
    }

    fun setEnabled(enable: Boolean) {
        enabled = enable
        prefs.edit { putBoolean(KEY_PERF_OVERLAY, enable) }
        btnPerfOverlay.isSelected = enable
        btnPerfOverlay.setText(if (enable) R.string.perf_overlay_on else R.string.perf_overlay_off)
        if (enable) {
            perfOverlay.visibility = View.VISIBLE
            lastHuePackets = hueStats().packetsSent
            lastHuePpsTimeNs = System.nanoTime()
            smoothedHuePps = 0f
            displayedFps = 0f          // count up from zero — a small flourish on open
            shownFps = -1
            perfHandler.removeCallbacks(perfPoller)
            perfHandler.removeCallbacks(perfFpsTicker)
            perfHandler.post(perfPoller)
            perfHandler.post(perfFpsTicker)
        } else {
            perfOverlay.visibility = View.GONE
            perfHandler.removeCallbacks(perfPoller)
            perfHandler.removeCallbacks(perfFpsTicker)
        }
    }

    fun onResume() {
        if (enabled) {
            perfHandler.post(perfPoller)
            perfHandler.post(perfFpsTicker)
        }
    }

    fun onPause() {
        perfHandler.removeCallbacks(perfPoller)
        perfHandler.removeCallbacks(perfFpsTicker)
    }

    fun onDestroy() {
        perfHandler.removeCallbacks(perfPoller)
        perfHandler.removeCallbacks(perfFpsTicker)
    }

    private fun updatePerfOverlay() {
        if (!enabled) return

        val audioMs = NativeBridge.nativeGetAudioCallbackMs()
        val rate = NativeBridge.nativeGetSampleRate()
        // Derive frame time from the (already EMA-smoothed) fps so the readout is
        // calm and consistent with the hero number, instead of the raw per-frame
        // dt which flickers every frame. Updated on the slow 500 ms cadence.
        val frameMs = if (displayedFps > 1f) 1000f / displayedFps else glView.rendererFrameTimeMs
        perfFrameMs.text = "%.1f ms".format(frameMs)

        val sb = android.text.SpannableStringBuilder()
        val dim = activity.getColor(R.color.text_dim)
        val amber = 0xFFFFBB33.toInt()

        // Each row: a dim mono label padded to a fixed column, then the value.
        fun appendRow(label: String, value: String, valueColor: Int = 0) {
            val start = sb.length
            sb.append(label.uppercase().padEnd(11))
            sb.setSpan(android.text.style.ForegroundColorSpan(dim), start, sb.length, 0)
            val vStart = sb.length
            sb.append(value)
            if (valueColor != 0) {
                sb.setSpan(android.text.style.ForegroundColorSpan(valueColor), vStart, sb.length, 0)
            }
            sb.append("\n")
        }

        // Hardware load → CPU time within the GL frame.
        val load = NativeBridge.nativeGetHardwareLoad()
        val cpuMs = load[0] / 1000f
        val cpuPercent = if (frameMs > 0) (cpuMs / frameMs * 100f).coerceIn(0f, 100f) else 0f
        appendRow("CPU", "%.1f ms · %.0f%%".format(cpuMs, cpuPercent))

        // Memory Usage
        val debugMem = android.os.Debug.MemoryInfo()
        android.os.Debug.getMemoryInfo(debugMem)
        val javaMb = debugMem.dalvikPss / 1024
        val nativeMb = debugMem.nativePss / 1024
        val gfxMb = debugMem.getMemoryStat("summary.graphics")?.toIntOrNull()?.div(1024) ?: 0
        val totalMb = debugMem.totalPss / 1024

        appendRow("RAM TOTAL", "%d MB".format(totalMb))
        appendRow("  JAVA", "%d MB".format(javaMb))
        appendRow("  NATIVE", "%d MB".format(nativeMb))
        appendRow("  GRAPHICS", "%d MB".format(gfxMb))

        // Audio capture.
        appendRow("Audio", "%d Hz · %.1f ms".format(rate, audioMs))

        // System audio / jitter (internal-audio mode only).
        if (isSystemAudioMode()) {
            val metrics = NativeBridge.nativeGetSystemAudioMetrics()
            val jitter = metrics[1]
            // System audio is inherently buffered by Android (often ~40ms blocks),
            // so we describe the state rather than raise an alarmist colour.
            val status = if (jitter > 80f) "bursty" else "buffered"
            appendRow("Shared", "%.1f ms · %s".format(jitter, status))
        }

        // Ableton Link.
        if (LinkSync.enabled) {
            val bpm = NativeBridge.nativeLinkTempo()
            val peers = NativeBridge.nativeLinkPeers()
            appendRow("Link", "%.0f bpm · %d peer%s".format(bpm, peers, if (peers == 1) "" else "s"))
        }

        // Philips Hue.
        val hue = hueStats()
        if (hue.active) {
            val sent = hue.packetsSent
            val nowNs = System.nanoTime()
            val elapsedS = (nowNs - lastHuePpsTimeNs) / 1_000_000_000.0
            val rawPps = if (elapsedS > 0.01) ((sent - lastHuePackets) / elapsedS).toFloat() else 0f
            lastHuePackets = sent
            lastHuePpsTimeNs = nowNs
            smoothedHuePps += (rawPps - smoothedHuePps) * 0.3f
            val drops = hue.packetsFailed
            val rttStr = if (hue.rttMs >= 0) "%d ms".format(hue.rttMs) else "—"
            appendRow("Hue", "%.0f pps · %s".format(smoothedHuePps, rttStr), if (drops > 0) amber else 0)
        }

        // Trim the trailing newline.
        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)

        perfDetail.text = sb
    }

    /** Render the giant eased FPS number: brand white when healthy, amber/red when struggling. */
    private fun renderHeroFps() {
        val refreshRate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }
        val v = kotlin.math.min(displayedFps, refreshRate + 0.5f)

        // When we're essentially locked to a vsync rate (60/90/120/144), show that
        // exact number — otherwise a ~60.5 reading flickers between 60 and 61. Off
        // a locked rate, a small deadband stops noise from twitching the integer.
        val snapped = LOCKED_RATES.firstOrNull { abs(v - it) <= FPS_SNAP_TOL }
        val target = snapped ?: v.roundToInt()
        val changed = when {
            shownFps < 0 -> true
            snapped != null -> shownFps != snapped
            else -> abs(v - shownFps) >= FPS_HYSTERESIS
        }
        if (changed) {
            val currentStr = (perfFpsValue.currentView as? TextView)?.text?.toString() ?: "0"
            val currentInt = currentStr.toIntOrNull() ?: 0

            if (target > currentInt) {
                perfFpsValue.inAnimation = AnimationUtils.loadAnimation(activity, R.anim.fps_in_up)
                perfFpsValue.outAnimation = AnimationUtils.loadAnimation(activity, R.anim.fps_out_up)
            } else if (target < currentInt) {
                perfFpsValue.inAnimation = AnimationUtils.loadAnimation(activity, R.anim.fps_in_down)
                perfFpsValue.outAnimation = AnimationUtils.loadAnimation(activity, R.anim.fps_out_down)
            }

            shownFps = target
            perfFpsValue.setText(target.toString())
        }

        val color = when {
            v < 30f -> 0xFFFF4444.toInt()
            v < 55f -> 0xFFFFBB33.toInt()
            else -> activity.getColor(R.color.text_primary)
        }
        (perfFpsValue.getChildAt(0) as? TextView)?.setTextColor(color)
        (perfFpsValue.getChildAt(1) as? TextView)?.setTextColor(color)
    }

    companion object {
        private const val KEY_PERF_OVERLAY = "perf_overlay_enabled"
        // FPS readout: snap to a vsync rate within this many fps (kills 60↔61
        // flicker); off a locked rate, require this much change before re-drawing.
        private val LOCKED_RATES = intArrayOf(60, 90, 120, 144)
        private const val FPS_SNAP_TOL = 6.0f
        private const val FPS_HYSTERESIS = 1.5f
    }
}
