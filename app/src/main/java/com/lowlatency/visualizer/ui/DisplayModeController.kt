package com.lowlatency.visualizer.ui

import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.text.format.DateFormat
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import com.lowlatency.visualizer.BeatBus
import com.lowlatency.visualizer.BeatSettings
import com.lowlatency.visualizer.LinkSync
import com.lowlatency.visualizer.NativeBridge
import com.lowlatency.visualizer.R
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Ambient "Display Mode" — a deliberate full-screen standby
 * an oversized Inter ExtraLight clock,
 * the live Ableton Link BPM (Space Mono), and an audio-presence meter, over the
 * dimmed live scene. Typography-as-spectacle, chrome-free; entered from Settings
 * and exited by tapping anywhere (a transient hint says so).
 *
 * Burn-in-safe: the content group is slowly pixel-shifted on an orbit and
 * auto-dims after entry. A single ~20 Hz ticker drives everything.
 */
class DisplayModeController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val onSwipeScene: (Int) -> Unit,
    private val onOpenMenu: () -> Unit,
) {
    private lateinit var overlay: View
    private lateinit var content: View
    private lateinit var clock: TextView
    private lateinit var date: TextView
    private lateinit var bpm: TextView
    private lateinit var bpmRow: View
    private lateinit var presenceFill: View
    private lateinit var exitHint: TextView
    private lateinit var cutoutClock: CutoutClockView
    private lateinit var styleBtn: Button

    var isActive = false
        private set

    /** When true, Ambient Mode renders the "window into the music" cutout clock
     *  instead of the classic solid clock + meter stack. Persisted; applied on entry. */
    var windowStyle = false
        private set

    private var shiftRadiusPx = 0f
    private var smoothedPresence = 0f
    private var elapsedMs = 0L
    private var lastClockMinute = -1
    private var lastBpmShown = -2
    private var phase = 0.0

    // Exit needs a confirming second tap (guards against accidental presses): the
    // first tap arms this window and shows "tap again to exit"; it disarms if no
    // second tap arrives in time.
    private var exitArmed = false
    private val disarmExit = Runnable {
        exitArmed = false
        hideHint()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val ticker = object : Runnable {
        override fun run() {
            tick()
            handler.postDelayed(this, FRAME_MS)
        }
    }

    fun bind() {
        overlay = activity.findViewById(R.id.display_mode_overlay)
        content = activity.findViewById(R.id.display_mode_content)
        clock = activity.findViewById(R.id.display_clock)
        date = activity.findViewById(R.id.display_date)
        bpm = activity.findViewById(R.id.display_bpm)
        bpmRow = activity.findViewById(R.id.display_bpm_row)
        presenceFill = activity.findViewById(R.id.display_presence_fill)
        exitHint = activity.findViewById(R.id.display_exit_hint)
        cutoutClock = activity.findViewById(R.id.display_clock_window)
        cutoutClock.setTypefaces(
            ResourcesCompat.getFont(activity, R.font.inter_medium),
            ResourcesCompat.getFont(activity, R.font.space_mono),
        )
        presenceFill.pivotX = 0f
        shiftRadiusPx = SHIFT_RADIUS_DP * activity.resources.displayMetrics.density
        wireOverlayGestures()

        // The style chooser only makes sense inside Ambient Mode (you reach the menu
        // there by swiping up, and the change previews live), so it's greyed out
        // until Ambient Mode is active. Defaults to the classic clock.
        styleBtn = activity.findViewById(R.id.btn_ambient_style)
        styleBtn.setOnClickListener { setWindowStyle(!windowStyle) }
        setWindowStyle(prefs.getBoolean(KEY_WINDOW_STYLE, false))
        updateStyleAvailability()
    }

    /** Grey the style toggle out unless Ambient Mode is active (then it previews live). */
    private fun updateStyleAvailability() {
        styleBtn.isEnabled = isActive
        styleBtn.alpha = if (isActive) 1f else DISABLED_ALPHA
    }

    /** Switch the standby style. Persists; if Ambient Mode is open, applies live. */
    fun setWindowStyle(on: Boolean) {
        windowStyle = on
        prefs.edit { putBoolean(KEY_WINDOW_STYLE, on) }
        styleBtn.isSelected = on
        styleBtn.setText(if (on) R.string.ambient_style_window else R.string.ambient_style_classic)
        if (isActive) applyStyle()
    }

    /** Show the views for the active style; the two styles are mutually exclusive. */
    private fun applyStyle() {
        if (windowStyle) {
            overlay.setBackgroundColor(Color.TRANSPARENT)   // the cutout supplies the dark field
            content.visibility = View.GONE
            cutoutClock.visibility = View.VISIBLE
            cutoutClock.setShift(0f, 0f)
            renderWindowClock()
        } else {
            overlay.setBackgroundColor(BASIC_SCRIM)
            cutoutClock.visibility = View.GONE
            content.visibility = View.VISIBLE
            content.alpha = 1f
            content.translationX = 0f
            content.translationY = 0f
            renderClock(force = true)
            renderBpm(force = true)
            renderPresence()
        }
    }

    /** Tap (twice) to exit; horizontal swipe browses scenes; swipe up opens settings. */
    @SuppressLint("ClickableViewAccessibility")
    private fun wireOverlayGestures() {
        val gestures = GestureDetector(activity, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapUp(e: MotionEvent): Boolean { onTapToExit(); return true }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                if (abs(vx) > abs(vy) && abs(vx) > SWIPE_VELOCITY) {
                    onSwipeScene(if (vx < 0) 1 else -1)   // left = next, right = prev (matches canvas)
                    disarmExitNow()                        // a swipe is deliberate — cancel any pending exit
                    return true
                }
                if (vy < 0 && abs(vy) > abs(vx) && abs(vy) > SWIPE_VELOCITY) {
                    onOpenMenu()                           // swipe up → settings, over the live overlay
                    disarmExitNow()
                    return true
                }
                return false
            }
        })
        overlay.setOnTouchListener { _, ev -> gestures.onTouchEvent(ev) }
    }

    /** First tap arms the exit + shows the prompt; a second tap within the window exits. */
    private fun onTapToExit() {
        if (exitArmed) {
            exit()
        } else {
            exitArmed = true
            showHint(R.string.ambient_mode_hint_again)
            handler.removeCallbacks(disarmExit)
            handler.postDelayed(disarmExit, EXIT_ARM_MS)
        }
    }

    private fun disarmExitNow() {
        exitArmed = false
        handler.removeCallbacks(disarmExit)
        hideHint()
    }

    fun enter() {
        if (isActive) return
        isActive = true
        updateStyleAvailability()
        exitArmed = false
        handler.removeCallbacks(disarmExit)
        smoothedPresence = 0f
        elapsedMs = 0L
        lastClockMinute = -1
        lastBpmShown = -2
        phase = 0.0
        applyStyle()
        overlay.alpha = 0f
        overlay.visibility = View.VISIBLE
        overlay.animate().alpha(1f).setDuration(ENTER_FADE_MS).start()
        flashExitHint()
        handler.removeCallbacks(ticker)
        handler.post(ticker)
    }

    fun exit() {
        if (!isActive) return
        isActive = false
        updateStyleAvailability()
        exitArmed = false
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(disarmExit)
        exitHint.animate().cancel()
        overlay.animate().alpha(0f).setDuration(EXIT_FADE_MS)
            .withEndAction { overlay.visibility = View.GONE }
            .start()
    }

    fun onResume() {
        if (isActive) { handler.removeCallbacks(ticker); handler.post(ticker) }
    }

    fun onPause() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(disarmExit)
        exitArmed = false
    }

    fun onDestroy() {
        handler.removeCallbacks(ticker)
        handler.removeCallbacks(disarmExit)
    }

    /** On entry, briefly show how to exit ("tap twice"), then fade it away. */
    private fun flashExitHint() {
        exitHint.setText(R.string.ambient_mode_hint)
        exitHint.animate().cancel()
        exitHint.alpha = 0f
        exitHint.animate().alpha(0.7f).setDuration(450L).withEndAction {
            exitHint.animate().alpha(0f).setStartDelay(2200L).setDuration(700L).start()
        }.start()
    }

    /** Show the armed "tap again to exit" prompt and hold it until disarm/exit. */
    private fun showHint(textRes: Int) {
        exitHint.setText(textRes)
        exitHint.animate().cancel()
        exitHint.animate().alpha(0.85f).setDuration(200L).start()
    }

    private fun hideHint() {
        exitHint.animate().cancel()
        exitHint.animate().alpha(0f).setDuration(500L).start()
    }

    private fun tick() {
        elapsedMs += FRAME_MS
        if (windowStyle) {
            // The visuals reacting *through* the type are the spectacle; no meter,
            // and no auto-dim (the mask is already near-black, the holes always move).
            renderWindowClock()
            renderPixelShift()
        } else {
            renderClock(force = false)
            renderBpm(force = false)
            renderPresence()
            renderPixelShift()
            renderAutoDim()
        }
    }

    /** Feed the cutout the time + a date/BPM subline (cheap; the view no-ops if
     *  nothing changed). BPM is folded in here since the classic meter is hidden. */
    private fun renderWindowClock() {
        val cal = Calendar.getInstance()
        val pattern = if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm"
        val time = DateFormat.format(pattern, cal).toString()
        val dateStr = DateFormat.format("EEE d MMM", cal).toString().uppercase()
        val sub = if (LinkSync.enabled) {
            val tempo = NativeBridge.nativeLinkTempo().roundToInt()
            if (tempo > 0) "$dateStr   ·   $tempo BPM" else dateStr
        } else {
            dateStr
        }
        cutoutClock.setText(time, sub)
    }

    private fun renderClock(force: Boolean) {
        val cal = Calendar.getInstance()
        val minuteOfDay = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (!force && minuteOfDay == lastClockMinute) return
        lastClockMinute = minuteOfDay
        val pattern = if (DateFormat.is24HourFormat(activity)) "HH:mm" else "h:mm"
        clock.text = DateFormat.format(pattern, cal)
        date.text = DateFormat.format("EEE d MMM", cal)
    }

    private fun renderBpm(force: Boolean) {
        // No BPM source unless Ableton Link is on — hide the whole readout when off.
        val linkOn = LinkSync.enabled
        val shown = if (linkOn) NativeBridge.nativeLinkTempo().roundToInt() else -1
        if (!force && shown == lastBpmShown) return
        lastBpmShown = shown
        if (linkOn) {
            bpmRow.visibility = View.VISIBLE
            bpm.text = if (shown > 0) shown.toString() else "—"
        } else {
            bpmRow.visibility = View.GONE
        }
    }

    private fun renderPresence() {
        val full = BeatSettings.levelFull.coerceAtLeast(1e-4f)
        val frac = (BeatBus.level / full).coerceIn(0f, 1f)
        smoothedPresence += (frac - smoothedPresence) * PRESENCE_SMOOTH
        presenceFill.scaleX = smoothedPresence.coerceIn(0f, 1f)
    }

    private fun renderPixelShift() {
        phase += SHIFT_SPEED
        val dx = (sin(phase) * shiftRadiusPx).toFloat()
        val dy = (cos(phase * 0.7) * shiftRadiusPx).toFloat()
        if (windowStyle) {
            cutoutClock.setShift(dx, dy)
        } else {
            content.translationX = dx
            content.translationY = dy
        }
    }

    private fun renderAutoDim() {
        val t = (elapsedMs.toFloat() / AUTO_DIM_MS).coerceIn(0f, 1f)
        content.alpha = 1f - (1f - AUTO_DIM_MIN_ALPHA) * t
    }

    companion object {
        private const val KEY_WINDOW_STYLE = "ambient_window_style"
        private const val BASIC_SCRIM = 0xD9000000.toInt()   // classic style's dim wash over the scene
        private const val DISABLED_ALPHA = 0.4f              // greyed style toggle when Ambient Mode is off
        private const val FRAME_MS = 50L            // 20 Hz: presence stays live, cost negligible
        private const val ENTER_FADE_MS = 280L
        private const val EXIT_FADE_MS = 220L
        private const val EXIT_ARM_MS = 2500L     // window for the confirming second tap
        private const val SWIPE_VELOCITY = 800f   // px/s fling threshold to change scene
        private const val PRESENCE_SMOOTH = 0.25f
        private const val SHIFT_SPEED = 0.0022       // radians/tick — a ~2.5-min orbit, imperceptibly slow but still migrates pixels for burn-in
        // The digits themselves change every minute (their lit pixels move on their
        // own), so the only truly-static element is the colon. A small drift is
        // plenty to migrate it — keep the clock visibly centred rather than wandering.
        private const val SHIFT_RADIUS_DP = 4f
        private const val AUTO_DIM_MS = 25_000f
        private const val AUTO_DIM_MIN_ALPHA = 0.55f
    }
}
