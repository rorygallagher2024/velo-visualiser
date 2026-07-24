package com.lowlatency.visualizer.gl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the metering scale's contract — the one thing both instruments
 * (Mechanical Meter, Level Meter) depend on and the one thing repeated revisions
 * have broken: that a given dBFS always maps to the same deflection.
 *
 * The dial-face shaping in [MeterCalibration.position] is easy to mistake for the
 * auto-gain the class exists to refuse, so these tests pin the properties that
 * tell them apart — fixed endpoints, monotonic, stateless — alongside the quiet-end
 * lift the shaping was added for.
 */
class MeterCalibrationTest {

    private val calibration = MeterCalibration()

    /** dB-linear position, i.e. the scale before dial-face shaping. */
    private fun linear(db: Float): Float =
        ((db - MeterCalibration.FLOOR_DB) /
            (MeterCalibration.CEIL_DB - MeterCalibration.FLOOR_DB)).coerceIn(0f, 1f)

    @Test
    fun `endpoints are the fixed dBFS range`() {
        assertEquals(0f, calibration.position(MeterCalibration.FLOOR_DB), 1e-6f)
        assertEquals(1f, calibration.position(MeterCalibration.CEIL_DB), 1e-6f)
    }

    @Test
    fun `levels outside the range clamp instead of running off the dial`() {
        assertEquals(0f, calibration.position(-120f), 1e-6f)
        assertEquals(1f, calibration.position(12f), 1e-6f)
    }

    @Test
    fun `position rises monotonically with level`() {
        var previous = -1f
        var db = MeterCalibration.FLOOR_DB
        while (db <= MeterCalibration.CEIL_DB) {
            val p = calibration.position(db)
            assertTrue("position must never fall as level rises (at $db dBFS)", p > previous)
            previous = p
            db += 0.5f
        }
    }

    /**
     * The whole point of the shaping: a phone mic in an ordinary room sits around
     * -55…-45 dBFS, which dB-linear buried in the bottom sliver of the arc.
     */
    @Test
    fun `quiet end gets more travel than a dB-linear scale`() {
        for (db in intArrayOf(-55, -50, -45, -40)) {
            val shaped = calibration.position(db.toFloat())
            assertTrue(
                "$db dBFS should sit higher than dB-linear (was $shaped)",
                shaped > linear(db.toFloat()) + 0.05f,
            )
        }
        // Roughly doubled at the bottom, and still legible rather than pinned.
        assertEquals(0.155f, calibration.position(-55f), 0.02f)
        assertEquals(0.261f, calibration.position(-50f), 0.02f)
    }

    /** The loud end must not be flattened into a useless huddle near the top. */
    @Test
    fun `music range keeps a usable spread`() {
        val quietMusic = calibration.position(-20f)
        val loudMaster = calibration.position(-10f)
        assertTrue("-20 dBFS should stay below 4/5 of travel", quietMusic < 0.8f)
        assertTrue("-10 dBFS should stay below full scale", loudMaster < 0.95f)
        assertTrue("music range needs visible separation", loudMaster - quietMusic > 0.1f)
    }

    /**
     * The scale is a pure function of level: no history, no source awareness. This
     * is what makes it a meter rather than an auto-gain — [MeterCalibration.update]
     * tracks the room only to decide when the instrument is idle.
     */
    @Test
    fun `scale never adapts to what it has been fed`() {
        val before = calibration.position(-40f)
        repeat(2000) { calibration.update(-6f, 0.016f) }
        assertEquals(before, calibration.position(-40f), 1e-6f)
        repeat(2000) { calibration.update(-90f, 0.016f) }
        assertEquals(before, calibration.position(-40f), 1e-6f)
    }
}
