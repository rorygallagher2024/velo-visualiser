package com.lowlatency.visualizer.ui

import android.content.SharedPreferences
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.edit
import com.lowlatency.visualizer.R
import kotlin.math.abs

/**
 * First-run "make a sound" moment.
 *
 * A user can't see latency; the gallery of visualisers looks like every other
 * music app. So on the very first launch, once the GL intro has finished and the
 * mic is live, we prompt "Make a sound" — the user claps or speaks, the canvas
 * reacts instantly and a haptic pulse lands the same moment. You *cause* the
 * reaction and *feel* it; no numbers, the immediacy speaks for itself. (An
 * earlier version displayed a measured "reacted in N ms" figure, but the
 * callback-period number understates true audio→photon latency, so it was cut
 * rather than overclaim.)
 *
 * Sequencing is delicate on a fresh install: the mic-permission dialog only appears
 * *after* the intro, so we can't prompt until audio is actually flowing. The host
 * calls [start] when the intro finishes, [onMicStarted] when the stream goes live,
 * and forwards PCM to [onPcm]; the moment begins only once both have happened. If
 * the mic never starts (permission denied), a safety timeout completes quietly so
 * the normal gesture hint still shows ([onComplete]). First-run only — the flag is
 * consumed when the moment becomes visible, so it never nags again.
 */
class FeelTheSpeedController(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val vibrate: () -> Unit,
    private val onComplete: () -> Unit,
) {
    private lateinit var reveal: TextView

    @Volatile private var waitingForMic = false
    @Volatile private var active = false       // prompt visible, listening for a transient
    @Volatile private var revealed = false
    private var pending: Runnable? = null

    fun bind() {
        reveal = activity.findViewById(R.id.moat_reveal)
    }

    /** True only on the very first launch. */
    fun shouldPlay(): Boolean = !prefs.getBoolean(KEY_SHOWN, false)

    /**
     * Intro finished (first run). If the mic is already live (permission was granted
     * on a prior run) begin at once; otherwise wait for [onMicStarted].
     */
    fun start(micAlreadyLive: Boolean) {
        if (micAlreadyLive) {
            begin()
            return
        }
        waitingForMic = true
        // Safety: if the mic never starts (e.g. permission denied), don't strand the
        // user without the follow-on gesture hint.
        schedule(WAIT_FOR_MIC_MS) { if (waitingForMic) { waitingForMic = false; onComplete() } }
    }

    /** The mic stream has gone live — show the prompt and start listening. */
    fun onMicStarted() {
        if (!waitingForMic) return
        waitingForMic = false
        begin()
    }

    /** GL-thread PCM tap; cheap no-op unless the moment is actively listening. */
    fun onPcm(pcm: FloatArray) {
        if (!active || revealed) return
        var peak = 0f
        for (s in pcm) { val a = abs(s); if (a > peak) peak = a }
        if (peak >= TRIGGER_PEAK) {
            revealed = true
            reveal.post { acknowledge() }
        }
    }

    private fun begin() {
        // Consume the flag the moment it becomes visible, so a mid-moment background
        // can't replay it.
        prefs.edit { putBoolean(KEY_SHOWN, true) }
        active = true
        showLine(activity.getString(R.string.moat_prompt))
        // No sound in time → a gentle nudge, still listening, then complete.
        schedule(NUDGE_AFTER_MS) { if (!revealed) nudge() }
    }

    /** Sound heard: the canvas is already reacting — land the haptic and bow out. */
    private fun acknowledge() {
        vibrate()
        schedule(ACK_HOLD_MS) { finish() }
    }

    private fun nudge() {
        showLine(activity.getString(R.string.moat_nudge))
        schedule(NUDGE_HOLD_MS) { if (!revealed) finish() }
    }

    private fun finish() {
        active = false
        reveal.animate().alpha(0f).setDuration(600L)
            .withEndAction { reveal.visibility = View.GONE; onComplete() }.start()
    }

    private fun showLine(text: String) {
        reveal.text = text
        if (reveal.visibility != View.VISIBLE || reveal.alpha < 1f) {
            reveal.visibility = View.VISIBLE
            reveal.animate().cancel()
            reveal.alpha = 0f
            reveal.animate().alpha(1f).setDuration(350L).start()
        }
    }

    private fun schedule(delay: Long, action: () -> Unit) {
        pending?.let { reveal.removeCallbacks(it) }
        val r = Runnable { action() }
        pending = r
        reveal.postDelayed(r, delay)
    }

    fun onDestroy() {
        pending?.let { reveal.removeCallbacks(it) }
    }

    companion object {
        private const val KEY_SHOWN = "moat_reveal_shown"
        private const val TRIGGER_PEAK = 0.15f       // a clap / snap / loud word
        private const val WAIT_FOR_MIC_MS = 6000L     // bail to the gesture hint if mic never starts
        private const val NUDGE_AFTER_MS = 4500L      // prompt → nudge if no sound
        private const val NUDGE_HOLD_MS = 3200L
        private const val ACK_HOLD_MS = 700L          // linger just long enough to feel the pulse
    }
}
