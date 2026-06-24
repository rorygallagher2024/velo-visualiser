package com.lowlatency.visualizer.ui

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.TextSwitcher
import android.widget.TextView
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import com.lowlatency.visualizer.VisualizerSurfaceView
import com.lowlatency.visualizer.hue.HueLightController
import com.lowlatency.visualizer.LinkSync

class PerfOverlayController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val glView: VisualizerSurfaceView,
    private val hueController: HueLightController,
    private val systemAudioProvider: () -> Boolean
) {
    private val perfOverlay: View = activity.findViewById(R.id.perf_overlay)
    private val perfFpsValue: TextSwitcher = activity.findViewById(R.id.perf_fps_value)
    private val perfFrameMs: TextView = activity.findViewById(R.id.perf_frame_ms)
    private val perfDetail: TextView = activity.findViewById(R.id.perf_detail)
    private val btnPerfOverlay: View = activity.findViewById(R.id.btn_perf_overlay)

    private var perfOverlayEnabled = false
    val isEnabled: Boolean get() = perfOverlayEnabled
    val height: Int get() = perfOverlay.height
    
    private var displayedFps = 0f
    private var shownFps = -1
    private var lastHuePackets = 0L

    private val perfHandler = Handler(Looper.getMainLooper())

    private val perfPoller = object : Runnable {
        override fun run() {
            updatePerfOverlay()
            perfHandler.postDelayed(this, 500L)
        }
    }

    private val perfFpsTicker = object : Runnable {
        override fun run() {
            displayedFps += (glView.rendererFps - displayedFps) * 0.22f
            renderHeroFps()
            perfHandler.postDelayed(this, 50L)
        }
    }

    init {
        btnPerfOverlay.setOnClickListener {
            setPerfOverlay(!perfOverlayEnabled)
        }
        setPerfOverlay(prefs.getBoolean(KEY_PERF_OVERLAY, false), persist = false)
    }

    fun onResume() {
        perfHandler.post(perfPoller)
        perfHandler.post(perfFpsTicker)
    }

    fun onPause() {
        perfHandler.removeCallbacksAndMessages(null)
    }

    fun setPerfOverlay(enabled: Boolean, persist: Boolean = true) {
        perfOverlayEnabled = enabled
        if (persist) prefs.edit().putBoolean(KEY_PERF_OVERLAY, enabled).apply()
        btnPerfOverlay.isSelected = enabled
        perfOverlay.visibility = if (enabled) View.VISIBLE else View.GONE
        if (enabled) {
            updatePerfOverlay()
        }
    }

    private fun updatePerfOverlay() {
        if (!perfOverlayEnabled) return

        val audioMs = NativeBridge.nativeGetAudioCallbackMs()
        val rate = NativeBridge.nativeGetSampleRate()
        val frameMs = if (displayedFps > 1f) 1000f / displayedFps else glView.rendererFrameTimeMs
        perfFrameMs.text = "%.1f ms".format(frameMs)

        val sb = android.text.SpannableStringBuilder()
        val dim = activity.getColor(R.color.text_dim)
        val amber = 0xFFFFBB33.toInt()

        fun appendRow(label: String, value: String, valueColor: Int = 0) {
            val start = sb.length
            sb.append(label.uppercase().padEnd(7))
            sb.setSpan(android.text.style.ForegroundColorSpan(dim), start, sb.length, 0)
            val vStart = sb.length
            sb.append(value)
            if (valueColor != 0) {
                sb.setSpan(android.text.style.ForegroundColorSpan(valueColor), vStart, sb.length, 0)
            }
            sb.append("\n")
        }

        val load = NativeBridge.nativeGetHardwareLoad()
        val cpuMs = load[0] / 1000f
        val cpuPercent = if (frameMs > 0) (cpuMs / frameMs * 100f).coerceIn(0f, 100f) else 0f
        appendRow("CPU", "%.1f ms · %.0f%%".format(cpuMs, cpuPercent))

        appendRow("Audio", "%d Hz · %.1f ms".format(rate, audioMs))

        if (systemAudioProvider()) {
            val metrics = NativeBridge.nativeGetSystemAudioMetrics()
            val jitter = metrics[1]
            val status = if (jitter > 80f) "bursty" else "buffered"
            appendRow("Shared", "%.1f ms · %s".format(jitter, status))
        }

        if (LinkSync.enabled) {
            val bpm = NativeBridge.nativeLinkTempo()
            val peers = NativeBridge.nativeLinkPeers()
            appendRow("Link", "%.0f bpm · %d peer%s".format(bpm, peers, if (peers == 1) "" else "s"))
        }

        if (hueController.isEnabled) {
            val sent = hueController.huePacketsSent
            val pps = ((sent - lastHuePackets) * 2).coerceAtLeast(0)
            lastHuePackets = sent
            val drops = hueController.huePacketsFailed
            appendRow("Hue", "%d pps · %d drop".format(pps, drops), if (drops > 0) amber else 0)
        }

        if (sb.isNotEmpty()) sb.delete(sb.length - 1, sb.length)
        perfDetail.text = sb
    }

    private fun renderHeroFps() {
        val refreshRate = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            activity.display?.refreshRate ?: 60f
        } else {
            @Suppress("DEPRECATION")
            activity.windowManager.defaultDisplay.refreshRate
        }
        val v = kotlin.math.min(displayedFps, refreshRate + 0.5f)
        
        val snapped = LOCKED_RATES.firstOrNull { Math.abs(v - it) <= FPS_SNAP_TOL }
        val target = snapped ?: Math.round(v)
        val changed = when {
            shownFps < 0 -> true
            snapped != null -> shownFps != snapped
            else -> Math.abs(v - shownFps) >= FPS_HYSTERESIS
        }
        if (changed) {
            val currentStr = (perfFpsValue.currentView as? TextView)?.text?.toString() ?: "0"
            val currentInt = currentStr.toIntOrNull() ?: 0
            
            if (target > currentInt) {
                perfFpsValue.inAnimation = android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.fps_in_up)
                perfFpsValue.outAnimation = android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.fps_out_up)
            } else if (target < currentInt) {
                perfFpsValue.inAnimation = android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.fps_in_down)
                perfFpsValue.outAnimation = android.view.animation.AnimationUtils.loadAnimation(activity, R.anim.fps_out_down)
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
        const val KEY_PERF_OVERLAY = "perf_overlay_enabled"
        private val LOCKED_RATES = intArrayOf(60, 90, 120, 144)
        private const val FPS_SNAP_TOL = 6.0f
        private const val FPS_HYSTERESIS = 1.5f
    }
}
