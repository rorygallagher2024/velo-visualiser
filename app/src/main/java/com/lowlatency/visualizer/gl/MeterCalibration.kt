package com.lowlatency.visualizer.gl

import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Shared scale + overload detection for the metering scenes (Mechanical Meter,
 * Level Meter), so the two instruments can never disagree about what "hot" means.
 *
 * **The scale is fixed: -60 to 0 dBFS, every source, no auto-gain anywhere.**
 *
 * A given deflection always means the same signal level. That is the entire value
 * of a meter, and every version that adapted the scale gave it away: a source
 * 20 dB below another rendered at the same height, so the reading answered "how
 * does this compare with itself lately" instead of "how loud is this".
 *
 * The mic is not an exception, though two revisions treated it as one. Its
 * converter has a defined full scale, so its dBFS is every bit as absolute as a
 * file's; it simply runs lower, and a meter's job is to show that rather than hide
 * it. The mic reading looking dead was never the fixed ceiling's fault either — it
 * was an *adaptive floor* that crept up under sustained level until the scale had
 * shrunk to 30 dB. Against a fixed -60 dB floor, loud room music lands near 3/4
 * and a mastered file near 7/8, which is both lively and true.
 *
 * What the fixed scale does NOT forbid is choosing where the marks sit on the face.
 * [position] shapes the dB-linear reading with a fixed exponent so the quiet end
 * gets a fairer share of the travel — see [DIAL_GAMMA]. Every source is shaped the
 * same way, so the reading stays absolute; a quiet mic simply stops being pinned
 * into the bottom sliver of the arc where nothing was legible.
 *
 * Expect RMS to stop short of the top: 0 dBFS RMS is a full-scale square wave, so
 * the last stretch is unreachable by design and stays reserved for peaks.
 *
 * The room level is still tracked, but only to decide when the instrument is at
 * rest ([atRest]) — never to move the scale. Idle is genuinely a relative question
 * ("is anything above the room floor"); level is not.
 *
 * [overLit] stays independent of all of it: an event detector on raw samples, so
 * nothing about where the scale sits can ever turn something red.
 *
 * Feed this the **raw** signal. The engine's mono analysis ring is scaled by
 * `kDigitalMonoGain` and a time-varying AGC, so dBFS measured there is not
 * absolute; the stereo ring is kept full scale and is the honest input.
 */
class  MeterCalibration {

    /** Tracked room / noise level in dBFS. Drives [atRest] only, never the scale. */
    var roomDb = FLOOR_DB
        private set

    /**
     * Overload lamp brightness, 0..1 — the *only* thing either instrument is
     * allowed to turn red. Red has to mean one thing across the app, and a
     * position on a scale is not it: a mark at some fraction of travel says
     * "loud", which every mastered track is, on purpose.
     */
    var overLit = 0f
        private set

    private var overHold = 0f

    /** Track the room level. [db] is the current level in dBFS. */
    fun update(db: Float, dt: Float) {
        // Minimum-follower: drops onto quiet gaps quickly, creeps up only under
        // sustained level, so it finds the room (or the record's quiet passages)
        // rather than the music sitting on top of it.
        if (roomDb.isNaN()) roomDb = db
        val tau = if (db < roomDb) NOISE_FALL_SEC else NOISE_RISE_SEC
        roomDb += (db - roomDb) * min(dt / tau, 1f)
    }

    /**
     * Map a dBFS level onto the fixed 0..1 scale. 1.0 means 0 dBFS: genuinely maxed.
     *
     * The scale is dB-linear, then shaped by [DIAL_GAMMA] so the quiet end gets more
     * of the travel. Straight dB-linear crushed it: a phone mic in an ordinary room
     * sits around -55…-45 dBFS, which landed in the bottom 8-25% of the arc, and
     * quiet passages read as no movement at all.
     *
     * This is a *scale shape*, not auto-gain. It is fixed, monotonic and identical
     * for every source, so a given dBFS still always maps to the same deflection —
     * the promise this whole class exists to keep. Only the spacing of the marks
     * changes, exactly as a printed meter face is free to space its own.
     */
    fun position(db: Float): Float =
        ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).coerceIn(0f, 1f).pow(DIAL_GAMMA)

    /**
     * True when nothing is playing above the room itself, so an instrument can dim
     * to standby. Deliberately relative: a noisy room and a silent one both idle
     * when their own ambience is all that is left, which a fixed dB threshold on an
     * absolute scale cannot express.
     */
    fun atRest(db: Float): Boolean = db <= roomDb + REST_MARGIN_DB

    /**
     * Advance the overload lamp from the raw window. Latches only briefly: a real
     * run of overs recurs, and a long latch fuses separate events into one solid
     * light, which is the opposite of what an indicator is for.
     */
    fun updateOverload(pcm: FloatArray, stride: Int, dt: Float) {
        overHold = if (hasOverload(pcm, stride)) OVER_HOLD_SEC else max(0f, overHold - dt)
        val target = if (overHold > 0f) 1f else 0f
        val rate = if (target > overLit) OVER_ATTACK_RATE else OVER_FADE_RATE
        overLit += (target - overLit) * min(rate * dt, 1f)
    }

    /** Forget the tracked floor — call when the audio source changes. */
    fun reset() {
        roomDb = Float.NaN
        overHold = 0f
        overLit = 0f
    }

    companion object {
        /**
         * True overload, by the broadcast "over" convention: a *run* of samples
         * pinned at full scale. A single full-scale sample proves nothing — every
         * modern master is brickwall-limited and kisses 0 dBFS constantly, so
         * testing one sample lights the lamp through the whole track. Genuine
         * clipping flat-tops the waveform, which is what a run detects.
         *
         * [stride] is the channel count of [pcm]: runs are counted per channel, or
         * an interleaved buffer would need both channels to clip on the same frame.
         */
        fun hasOverload(pcm: FloatArray, stride: Int): Boolean {
            // Scan only the shared recent window. The ring holds ~170 ms, and
            // re-testing all of it every frame re-arms the latch for as long as an
            // old event stays in the ring — stretching the 180 ms flash to ~350 ms
            // and fusing separate overloads into one solid light, the opposite of
            // what an indicator is for.
            val totalFrames = pcm.size / stride
            val start = (totalFrames - min(totalFrames, RECENT_FRAMES)) * stride
            for (ch in 0 until stride) {
                var run = 0
                var i = start + ch
                while (i < pcm.size) {
                    val s = pcm[i]
                    if (s >= FULL_SCALE || s <= -FULL_SCALE) {
                        run++
                        if (run >= OVER_RUN) return true
                    } else {
                        run = 0
                    }
                    i += stride
                }
            }
            return false
        }

        /**
         * Detector window, shared so the instruments measure the same thing. The
         * ring holds ~170 ms at 48 kHz; averaging all of it buries transients and
         * costs responsiveness no ballistic choice can win back. Ballistics belong
         * in the movement (the VU spring, the PPM release), never in the detector —
         * doing both integrates twice and the reading collapses on anything sharp.
         */
        const val RECENT_FRAMES = 2048      // ~43 ms at 48 kHz

        const val CEIL_DB = 0f              // digital full scale
        const val FLOOR_DB = -60f           // standard programme-meter range

        /**
         * Dial-face shaping exponent, applied to the dB-linear position (< 1 lifts
         * the quiet end). 0.75 roughly doubles the bottom of the range — -55 dBFS
         * moves from 8% of travel to 16%, -50 dBFS from 17% to 26% — while costing
         * the loud end little: -20 dBFS 67% → 74%, -10 dBFS 83% → 87%.
         *
         * Note this is the opposite curve to an analogue VU face, which is linear in
         * voltage and so crushes the bottom even harder than dB-linear does. That
         * shape suits a +3 dB-headroom broadcast meter fed a levelled line signal,
         * not a phone mic whose useful material lives 40 dB down.
         */
        private const val DIAL_GAMMA = 0.75f
        private const val REST_MARGIN_DB = 4f
        private const val NOISE_FALL_SEC = 0.6f
        private const val NOISE_RISE_SEC = 25f

        private const val FULL_SCALE = 0.999f
        private const val OVER_RUN = 3      // consecutive samples, per channel
        private const val OVER_HOLD_SEC = 0.18f    // short, so repeats read as flashes
        private const val OVER_ATTACK_RATE = 40f   // lights instantly
        private const val OVER_FADE_RATE = 9f      // and lets go quickly
    }
}
