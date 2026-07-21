package com.lowlatency.visualizer.gl

import kotlin.math.min

/**
 * Shared scale + overload detection for the metering scenes (Mechanical Meter,
 * Level Meter), so the two instruments can never disagree about what "hot" means.
 *
 * **The rule that keeps a meter honest: auto-range the floor, never the ceiling.**
 *
 * The floor is *sensitivity*. Adapting it is legitimate and necessary: a built-in
 * mic in a normal room sits 30-50 dB below full scale, and a meter pinned to a
 * fixed floor would show a stub that barely moves. Tracking the noise floor lets a
 * quiet room use most of the scale, which is the reactivity the instruments need.
 *
 * The ceiling is *headroom*, and adapting it is a lie. Once the top of the scale
 * follows the signal, "full deflection" only means "louder than this source has
 * been lately" — a relative statement — and any absolute mark drawn on that scale
 * (a red zone, an overload lamp) becomes meaningless. A previous revision moved
 * both ends, so a raised voice pinned the bar and reddened the needle while
 * sitting 40 dB below clipping. The ceiling here is fixed at 0 dBFS, full stop.
 *
 * Two consequences worth expecting, both correct:
 *  - An RMS reading can never actually reach the top (0 dBFS RMS would be a
 *    full-scale square wave), so the loudest masters ride ~80-85% and the very
 *    top stays reserved. That is what a real programme meter does.
 *  - Peak sits well above RMS, so a scene showing both puts the peak marker near
 *    the top on a mastered track while the body of the bar sits lower.
 *
 * Feed this the **raw** signal. The engine's mono analysis ring is scaled by
 * `kDigitalMonoGain` and a time-varying AGC, so dBFS measured there is not
 * absolute; the stereo ring is kept full scale and is the honest input.
 */
class  MeterCalibration {

    /** Bottom of the scale in dBFS, tracked from the noise floor. */
    var floorDb = FLOOR_MIN
        private set

    private var noiseDb = Float.NaN

    /** Advance the adaptive floor. [db] is the current level in dBFS. */
    fun update(db: Float, dt: Float) {
        // Minimum-follower: drops onto quiet gaps quickly, creeps up only under
        // sustained level, so the floor finds the room (or the record's quiet
        // passages) rather than the music sitting on top of it.
        if (noiseDb.isNaN()) noiseDb = db
        val tau = if (db < noiseDb) NOISE_FALL_SEC else NOISE_RISE_SEC
        noiseDb += (db - noiseDb) * min(dt / tau, 1f)
        floorDb = (noiseDb + NOISE_MARGIN_DB).coerceIn(FLOOR_MIN, FLOOR_MAX)
    }

    /** Map a dBFS level onto the 0..1 scale. 1.0 means 0 dBFS: genuinely maxed. */
    fun position(db: Float): Float = ((db - floorDb) / (CEIL_DB - floorDb)).coerceIn(0f, 1f)

    /** Forget the tracked floor — call when the audio source changes. */
    fun reset() {
        noiseDb = Float.NaN
        floorDb = FLOOR_MIN
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

        const val CEIL_DB = 0f              // absolute, always: digital full scale
        private const val FLOOR_MIN = -66f  // widest the scale ever opens
        private const val FLOOR_MAX = -30f  // narrowest, for a genuinely loud room
        private const val NOISE_MARGIN_DB = 4f
        private const val NOISE_FALL_SEC = 0.6f
        private const val NOISE_RISE_SEC = 25f

        private const val FULL_SCALE = 0.999f
        private const val OVER_RUN = 3      // consecutive samples, per channel
    }
}
