/*
 * Copyright 2026 TrueWind contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.github.kf6999.truewind.core.heading

import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.math.normalizeSignedDegrees
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Synthetic tracks from TESTING 2. 1 Hz fixes. */
class HeadingEstimatorTest {
    private fun err(a: Double, b: Double) = abs(normalizeSignedDegrees(a - b))

    /** GPS course noise grows as speed falls (roughly sigma ~ 3 deg at 8 m/s, 15 deg at 1.4 m/s). */
    private fun courseNoise(rnd: Random, speed: Double) = rnd.nextGaussian() * min(60.0, 20.0 / max(speed, 0.3))

    @Test fun noInput_noHeading() {
        val e = HeadingEstimator()
        val r = e.update(HeadingSample(0, null, 0.0))
        assertNull(r.headingDeg)
        assertEquals(HeadingSource.NONE, r.source)
    }

    @Test fun straightLine_smoothedWithinTolerance() {
        val rnd = Random(1)
        val e = HeadingEstimator()
        var maxErr = 0.0
        for (t in 0 until 300) {
            val truth = 47.0
            val r = e.update(HeadingSample(t * 1000L, normalizeDegrees(truth + courseNoise(rnd, 8.0)), 8.0, 5.0))
            if (t > 5) maxErr = max(maxErr, err(r.headingDeg!!, truth))
            assertEquals(HeadingSource.GPS_COURSE, r.source)
        }
        assertTrue("max err $maxErr", maxErr < 6.0)
    }

    @Test fun wrapAround_northbound() {
        val rnd = Random(2)
        val e = HeadingEstimator()
        for (t in 0 until 120) {
            val r = e.update(HeadingSample(t * 1000L, normalizeDegrees(rnd.nextGaussian() * 3.0), 8.0, 5.0))
            if (t > 5) assertTrue("${r.headingDeg}", err(r.headingDeg!!, 0.0) < 6.0)
        }
    }

    /**
     * Ride north, stop at a junction for 60 s (GPS course garbage, speed jitter), turn 90 deg and ride
     * east. Arrow must not swing more than 10 deg while stopped; must settle within 15 deg of the new
     * heading quickly after riding resumes.
     */
    private fun junction(withKaroo: Boolean): Pair<Double, Double> {
        val rnd = Random(3)
        val e = HeadingEstimator()
        var t = 0
        // 3 min riding north with Karoo HEADING tracking within a few degrees.
        while (t < 180) {
            e.update(HeadingSample(t * 1000L, normalizeDegrees(courseNoise(rnd, 7.0)), 7.0, 5.0,
                karooHeadingDeg = if (withKaroo) normalizeDegrees(rnd.nextGaussian() * 3.0) else null))
            t++
        }
        val atStop = e.currentHeadingDeg!!
        var swing = 0.0
        // 60 s stopped: speed jitter up to ~1.2 m/s, course random.
        repeat(60) {
            val speed = abs(rnd.nextGaussian() * 0.4)
            val r = e.update(HeadingSample(t * 1000L, rnd.nextDouble() * 360.0, speed, 8.0,
                karooHeadingDeg = if (withKaroo) normalizeDegrees(rnd.nextGaussian() * 3.0) else null))
            swing = max(swing, err(r.headingDeg!!, atStop))
            t++
        }
        // Turn 90 deg and accelerate at 1 m/s^2 to 7 m/s.
        var speed = 0.0
        var reached3At = -1
        var settledAt = -1
        repeat(60) {
            speed = min(7.0, speed + 1.0)
            if (speed >= 3.0 && reached3At < 0) reached3At = t
            val r = e.update(HeadingSample(t * 1000L, normalizeDegrees(90.0 + courseNoise(rnd, speed)), speed, 5.0,
                karooHeadingDeg = if (withKaroo) normalizeDegrees(90.0 + rnd.nextGaussian() * 3.0) else null))
            if (settledAt < 0 && err(r.headingDeg!!, 90.0) < 15.0) settledAt = t
            t++
        }
        return swing to (settledAt - reached3At).toDouble()
    }

    @Test fun junctionStopAndTurn_withoutKarooHeading() {
        val (swing, lag) = junction(withKaroo = false)
        println("junction withKaroo=false swing=$swing lag=$lag")
        assertTrue("swing $swing", swing <= 10.0)
        assertTrue("lag $lag s", lag <= 2.0)
    }

    @Test fun junctionStopAndTurn_withKarooHeading() {
        val (swing, lag) = junction(withKaroo = true)
        println("junction withKaroo=true swing=$swing lag=$lag")
        assertTrue("swing $swing", swing <= 10.0)
        assertTrue("lag $lag s", lag <= 2.0)
    }

    @Test fun stoppedFollowsTrustedKarooHeading_whenRiderTurnsBike() {
        val rnd = Random(4)
        val e = HeadingEstimator()
        var t = 0
        repeat(150) {
            e.update(HeadingSample(t * 1000L, normalizeDegrees(10.0 + courseNoise(rnd, 7.0)), 7.0, 5.0,
                karooHeadingDeg = normalizeDegrees(10.0 + rnd.nextGaussian() * 3.0)))
            t++
        }
        // Stopped, the bike is turned to 100 deg; trusted HEADING follows.
        var r: HeadingEstimate? = null
        repeat(15) {
            r = e.update(HeadingSample(t * 1000L, null, 0.0, 5.0, karooHeadingDeg = 100.0))
            t++
        }
        assertTrue(r!!.karooHeadingTrusted)
        assertEquals(HeadingSource.KAROO_HEADING, r!!.source)
        assertTrue("${r!!.headingDeg}", err(r!!.headingDeg!!, 100.0) < 2.0)
    }

    @Test fun untrustedKarooHeading_isNotFollowedWhenStopped() {
        val rnd = Random(5)
        val e = HeadingEstimator()
        var t = 0
        // HEADING off by 45 deg (e.g. badly calibrated magnetometer) while riding.
        repeat(150) {
            e.update(HeadingSample(t * 1000L, normalizeDegrees(10.0 + courseNoise(rnd, 7.0)), 7.0, 5.0,
                karooHeadingDeg = 55.0))
            t++
        }
        val held = e.currentHeadingDeg!!
        val r = e.update(HeadingSample(t * 1000L, null, 0.0, 5.0, karooHeadingDeg = 200.0))
        assertFalse(r.karooHeadingTrusted)
        assertEquals(HeadingSource.HELD, r.source)
        assertEquals(held, r.headingDeg!!, 1e-9)
    }

    /** 5 km/h switchback climb: 60 s legs alternating 30 / 210 deg, 12 s hairpins. */
    private fun switchbacks(withKaroo: Boolean): Double {
        val rnd = Random(6)
        val e = HeadingEstimator()
        val speed = 5.0 / 3.6
        var t = 0
        var truth = 30.0
        var sumErr = 0.0
        var n = 0
        repeat(6) { leg ->
            repeat(60) { s ->
                val r = e.update(HeadingSample(t * 1000L, normalizeDegrees(truth + courseNoise(rnd, speed)), speed, 6.0,
                    karooHeadingDeg = if (withKaroo) normalizeDegrees(truth + rnd.nextGaussian() * 5.0) else null))
                // Score after the first 15 s of each leg (allows settling after the hairpin).
                if (s >= 15) { sumErr += err(r.headingDeg!!, truth); n++ }
                t++
            }
            // Hairpin: 180 deg in 12 s, alternating direction.
            val dir = if (leg % 2 == 0) 1.0 else -1.0
            repeat(12) {
                truth = normalizeDegrees(truth + dir * 15.0)
                e.update(HeadingSample(t * 1000L, normalizeDegrees(truth + courseNoise(rnd, speed)), speed, 6.0,
                    karooHeadingDeg = if (withKaroo) normalizeDegrees(truth + rnd.nextGaussian() * 5.0) else null))
                t++
            }
        }
        return sumErr / n
    }

    @Test fun switchbackClimb_withKarooHeading() {
        val mae = switchbacks(withKaroo = true)
        println("switchbacks withKaroo=true MAE=$mae")
        assertTrue("MAE $mae", mae < 8.0)
    }

    @Test fun switchbackClimb_courseOnly() {
        val mae = switchbacks(withKaroo = false)
        println("switchbacks withKaroo=false MAE=$mae")
        assertTrue("MAE $mae", mae < 12.0)
    }

    @Test fun gpsOutlier_withPoorAccuracy_isRejected() {
        val rnd = Random(7)
        val e = HeadingEstimator()
        var t = 0
        repeat(60) {
            e.update(HeadingSample(t * 1000L, normalizeDegrees(45.0 + courseNoise(rnd, 8.0)), 8.0, 5.0)); t++
        }
        val before = e.currentHeadingDeg!!
        val r = e.update(HeadingSample(t * 1000L, 170.0, 8.0, 35.0)); t++
        assertTrue("moved ${err(r.headingDeg!!, before)}", err(r.headingDeg!!, before) < 1e-9)
        // Next good fix continues normally.
        val r2 = e.update(HeadingSample(t * 1000L, 45.0, 8.0, 5.0))
        assertTrue(err(r2.headingDeg!!, 45.0) < 5.0)
    }

    @Test fun confirmedJump_isAccepted_evenWithPoorAccuracy() {
        val e = HeadingEstimator()
        var t = 0
        repeat(20) { e.update(HeadingSample(t * 1000L, 0.0, 8.0, 30.0)); t++ }
        e.update(HeadingSample(t * 1000L, 120.0, 8.0, 30.0)); t++
        assertEquals(0.0, e.currentHeadingDeg!!, 1e-9)
        e.update(HeadingSample(t * 1000L, 125.0, 8.0, 30.0)); t++
        assertTrue("${e.currentHeadingDeg}", e.currentHeadingDeg!! > 60.0)
    }

    @Test fun routeBearing_usedInPoorGps() {
        val e = HeadingEstimator()
        var t = 0
        repeat(10) { e.update(HeadingSample(t * 1000L, 90.0, 8.0, 5.0, routeBearingDeg = 92.0)); t++ }
        assertEquals(HeadingSource.GPS_COURSE, e.update(HeadingSample(t * 1000L, 90.0, 8.0, 5.0, routeBearingDeg = 92.0)).source)
        t++
        var r: HeadingEstimate? = null
        repeat(10) { r = e.update(HeadingSample(t * 1000L, 200.0, 8.0, 60.0, routeBearingDeg = 92.0)); t++ }
        assertEquals(HeadingSource.ROUTE, r!!.source)
        assertEquals(92.0, r!!.headingDeg!!, 0.5)
    }

    @Test fun gapResetsSmoothing() {
        val e = HeadingEstimator()
        e.update(HeadingSample(0, 10.0, 8.0, 5.0))
        val r = e.update(HeadingSample(120_000, 200.0, 8.0, 5.0))
        assertEquals(200.0, r.headingDeg!!, 1e-9)
    }

    @Test fun outputAlwaysInRange_property() {
        val rnd = Random(8)
        val e = HeadingEstimator()
        repeat(20_000) { t ->
            val r = e.update(HeadingSample(t * 700L,
                if (rnd.nextInt(10) == 0) null else rnd.nextDouble() * 720.0 - 360.0,
                rnd.nextDouble() * 12.0,
                rnd.nextDouble() * 50.0,
                if (rnd.nextBoolean()) rnd.nextDouble() * 360.0 else null,
                if (rnd.nextInt(5) == 0) rnd.nextDouble() * 360.0 else null))
            r.headingDeg?.let { assertTrue("$it", it >= 0.0 && it < 360.0) }
        }
    }
}
