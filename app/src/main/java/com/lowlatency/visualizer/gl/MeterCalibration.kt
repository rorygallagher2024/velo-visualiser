package com.lowlatency.visualizer.gl

import com.lowlatency.visualizer.BeatSettings
import kotlin.math.max
import kotlin.math.min

/**
 * Shared scale + overload detection for the metering scenes (Mechanical Meter,
 * Level Meter), so the two instruments can never disagree about what "hot" means.
 *
 * **The rule that keeps a meter honest: never let the scale drive the red.**
 *
 * Position and overload answer different questions, and the failures all came from
 * conflating them. An early revision drew a red zone at a fraction of travel, so
 * any source loud enough for its own recent average went red — a raised voice
 * reddened a needle sitting 40 dB below clipping. [overLit] is now an event
 * detector on raw samples, entirely independent of where the scale happens to sit,
 * which is what lets the scale itself adapt freely where it needs to.
 *
 * And it does need to, per source, because the two families differ in whether an
 * absolute reference exists at all:
 *  - **Digital** (system / local / tone) has one. The ceiling is 0 dBFS, so full
 *    deflection means genuinely maxed. Expect RMS to top out ~80-85% on the
 *    loudest masters: 0 dBFS RMS is a full-scale square wave, so the very top is
 *    unreachable by design and stays reserved.
 *  - **Mic** has none. Its dBFS depends on the preamp, the distance and the room,
 *    and it runs 20-40 dB below full scale in every realistic case — a ceiling
 *    pinned at 0 dBFS confines loud music to the bottom third of the dial and the
 *    instrument looks dead. So the whole scale rides a tracked reference, and full
 *    deflection reads as "loud for this room", the only claim a mic can support.
 *
 * The floor is *sensitivity* in both modes: tracking the noise floor lets a quiet
 * room use most of the scale, and keeps room tone resting at zero rather than
 * floating the needle.
 *
 * Feed this the **raw** signal. The engine's mono analysis ring is scaled by
 * `kDigitalMonoGain` and a time-varying AGC, so dBFS measured there is not
 * absolute; the stereo ring is kept full scale and is the honest input.
 */
class  MeterCalibration {

    /** Bottom of the scale in dBFS, tracked from the noise floor. */
    var floorDb = FLOOR_MIN
        private set

    /** Top of the scale in dBFS: absolute for digital, tracked for the mic. */
    var ceilDb = CEIL_DB
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
    private var noiseDb = Float.NaN
    private var micRefDb = Float.NaN

    /** Advance the adaptive scale. [db] is the current level in dBFS. */
    fun update(db: Float, dt: Float) {
        // Minimum-follower: drops onto quiet gaps quickly, creeps up only under
        // sustained level, so the floor finds the room (or the record's quiet
        // passages) rather than the music sitting on top of it.
        if (noiseDb.isNaN()) noiseDb = db
        val tau = if (db < noiseDb) NOISE_FALL_SEC else NOISE_RISE_SEC
        noiseDb += (db - noiseDb) * min(dt / tau, 1f)

        if (BeatSettings.systemAudio) {
            floorDb = (noiseDb + NOISE_MARGIN_DB).coerceIn(FLOOR_MIN, FLOOR_MAX)
            ceilDb = CEIL_DB
            return
        }
        updateMicScale(db, dt)
    }

    /**
     * Mic scale: the reference chases the recent level (fast up to catch a loud
     * passage, slower down to re-centre) and the scale hangs off it, with far more
     * range below than above so a steady loud source rides ~3/4 and only a surge
     * reaches the top. Clamped at both ends so the extremes still read as extremes
     * instead of everything normalising to the same height.
     */
    private fun updateMicScale(db: Float, dt: Float) {
        if (micRefDb.isNaN()) micRefDb = db.coerceIn(MIC_REF_MIN, MIC_REF_MAX)
        val tau = if (db > micRefDb) MIC_ATTACK_SEC else MIC_RELEASE_SEC
        micRefDb += (db - micRefDb) * min(dt / tau, 1f)
        micRefDb = micRefDb.coerceIn(MIC_REF_MIN, MIC_REF_MAX)
        ceilDb = micRefDb + MIC_SPAN_ABOVE
        // Never below the room itself, or ambience floats the needle off its rest.
        floorDb = max(micRefDb - MIC_SPAN_BELOW, noiseDb + NOISE_MARGIN_DB)
    }

    /** Map a dBFS level onto the 0..1 scale. */
    fun position(db: Float): Float {
        val span = (ceilDb - floorDb).coerceAtLeast(1e-3f)
        return ((db - floorDb) / span).coerceIn(0f, 1f)
    }

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
        noiseDb = Float.NaN
        micRefDb = Float.NaN
        floorDb = FLOOR_MIN
        ceilDb = CEIL_DB
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
            for (ch in 0 until stride) {
                var run = 0
                var i = ch
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

        const val CEIL_DB = 0f              // digital full scale
        private const val FLOOR_MIN = -66f  // widest the digital scale ever opens
        private const val FLOOR_MAX = -30f  // narrowest, for a genuinely loud source
        private const val NOISE_MARGIN_DB = 4f
        private const val NOISE_FALL_SEC = 0.6f
        private const val NOISE_RISE_SEC = 25f

        // Mic auto-range. SPAN_BELOW:SPAN_ABOVE is 3:1, so a steady source settles
        // at 3/4 of the scale with a quarter left for anything louder.
        private const val MIC_REF_MIN = -50f        // most sensitive (quiet room)
        private const val MIC_REF_MAX = -15f        // least sensitive (loud room)
        private const val MIC_ATTACK_SEC = 0.8f     // rise toward loud
        private const val MIC_RELEASE_SEC = 4f      // fall back / re-centre
        private const val MIC_SPAN_BELOW = 24f
        private const val MIC_SPAN_ABOVE = 8f

        private const val FULL_SCALE = 0.999f
        private const val OVER_RUN = 3      // consecutive samples, per channel
        private const val OVER_HOLD_SEC = 0.18f    // short, so repeats read as flashes
        private const val OVER_ATTACK_RATE = 40f   // lights instantly
        private const val OVER_FADE_RATE = 9f      // and lets go quickly
    }
}
