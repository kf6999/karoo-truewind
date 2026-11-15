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
import io.github.kf6999.truewind.core.math.smoothAngle
import kotlin.math.abs
import kotlin.math.exp

/** Where the current heading estimate comes from. */
enum class HeadingSource { NONE, GPS_COURSE, BLEND, KAROO_HEADING, HELD, ROUTE }

/**
 * One input sample. All angles in degrees clockwise from north.
 *
 * @param timeMs sample time (injected clock; the estimator never reads a wall clock)
 * @param gpsCourseDeg GPS course over ground (`LOCATION.bearing`), null if missing
 * @param gpsSpeedMps GPS or wheel speed, m/s
 * @param gpsAccuracyM horizontal accuracy, m; null = unknown, treated as good
 * @param karooHeadingDeg Karoo `HEADING` stream value, null if absent
 * @param routeBearingDeg bearing of the current route segment; pass it only while navigating and on route
 */
data class HeadingSample(
    val timeMs: Long,
    val gpsCourseDeg: Double?,
    val gpsSpeedMps: Double,
    val gpsAccuracyM: Double? = null,
    val karooHeadingDeg: Double? = null,
    val routeBearingDeg: Double? = null,
)

/** Tuning (SPEC 4.5). Defaults are the spec values plus the extra knobs documented on each field. */
data class HeadingConfig(
    /** Above this speed GPS course alone is used. */
    val fullCourseSpeedMps: Double = 3.0,
    /** Below this speed the rider is treated as stopped. */
    val stoppedSpeedMps: Double = 1.0,
    /** Circular smoothing time constant for small course changes. */
    val tauSeconds: Double = 1.5,
    /**
     * Time constant used for large, confirmed course changes (turns) at riding speed, so the arrow
     * does not lag a junction turn by several fixes. Interpolated from [tauSeconds] at
     * [fastTurnStartDeg] to this value at [fastTurnFullDeg]. Not in SPEC; keeps turn lag within
     * upstream + 1 s (TESTING 3.6).
     */
    val turnTauSeconds: Double = 0.4,
    val fastTurnStartDeg: Double = 20.0,
    val fastTurnFullDeg: Double = 45.0,
    /** Course jumps larger than this need confirmation (see [jumpConfirmAccuracyM]). */
    val maxJumpDeg: Double = 60.0,
    /** Jumps are rejected in one fix when accuracy is worse than this. */
    val jumpConfirmAccuracyM: Double = 20.0,
    /** Use the route bearing when accuracy is worse than this and a route bearing is supplied. */
    val routeAccuracyM: Double = 25.0,
    /** HEADING must have tracked GPS course within this over [trustWindowMs] of riding to be followed when stopped. */
    val trustToleranceDeg: Double = 20.0,
    val trustWindowMs: Long = 120_000,
    /** Minimum number of comparisons inside the window before HEADING is trusted. */
    val trustMinSamples: Int = 30,
    /** A gap longer than this resets the smoothing (e.g. GPS loss); the next fix is taken directly. */
    val maxGapMs: Long = 30_000,
)

/** Current estimate. [headingDeg] is null until the first usable input. */
data class HeadingEstimate(
    val headingDeg: Double?,
    val source: HeadingSource,
    /** True when the Karoo HEADING stream currently qualifies to be followed while stopped. */
    val karooHeadingTrusted: Boolean,
)

/**
 * Heading fusion (SPEC 4.5): pure, clock-injected state machine. Feed one [HeadingSample] per fix
 * (about 1 Hz) to [update]. No allocations per update apart from the returned [HeadingEstimate].
 *
 * - speed >= 3 m/s: GPS course, circular exponential smoothing (tau 1.5 s; faster for confirmed turns)
 * - 1..3 m/s: complementary blend of course and Karoo HEADING, weight shifting linearly to HEADING
 *   as speed falls; smoothing slows as speed falls
 * - < 1 m/s: hold the last moving heading, or follow HEADING if it tracked course within 20 deg over the
 *   previous 2 min of riding
 * - course jumps > 60 deg in one fix are rejected when accuracy is worse than 20 m, and always below
 *   3 m/s (low-speed course is noise-dominated); a jump confirmed by the next fix is accepted
 * - on route with accuracy worse than 25 m: route segment bearing
 */
class HeadingEstimator(private val config: HeadingConfig = HeadingConfig()) {
    private var heading = Double.NaN
    private var lastTimeMs = Long.MIN_VALUE
    private var source = HeadingSource.NONE

    // Pending jump awaiting confirmation.
    private var pendingCourse = Double.NaN

    // Ring buffer of (riding time, |course - HEADING|) comparisons while moving.
    private val trustCapacity = 256
    private val trustTime = LongArray(trustCapacity)
    private val trustDiff = DoubleArray(trustCapacity)
    private var trustCount = 0
    private var trustHead = 0
    private var ridingTimeMs = 0L

    val currentHeadingDeg: Double? get() = if (heading.isNaN()) null else heading

    fun reset() {
        heading = Double.NaN
        lastTimeMs = Long.MIN_VALUE
        source = HeadingSource.NONE
        pendingCourse = Double.NaN
        trustCount = 0
        trustHead = 0
        ridingTimeMs = 0L
    }

    fun update(sample: HeadingSample): HeadingEstimate {
        val dtMs = if (lastTimeMs == Long.MIN_VALUE) 0L else (sample.timeMs - lastTimeMs).coerceAtLeast(0L)
        val gap = lastTimeMs != Long.MIN_VALUE && dtMs > config.maxGapMs
        lastTimeMs = sample.timeMs
        val dt = dtMs / 1000.0
        val speed = sample.gpsSpeedMps.takeIf { it.isFinite() } ?: 0.0
        val accuracy = sample.gpsAccuracyM
        val karoo = sample.karooHeadingDeg?.takeIf { it.isFinite() }?.let(::normalizeDegrees)
        val rawCourse = sample.gpsCourseDeg?.takeIf { it.isFinite() }?.let(::normalizeDegrees)

        if (gap) {
            pendingCourse = Double.NaN
        }

        // Route bearing in poor GPS.
        val route = sample.routeBearingDeg?.takeIf { it.isFinite() }
        if (route != null && accuracy != null && accuracy > config.routeAccuracyM && speed >= config.stoppedSpeedMps) {
            apply(normalizeDegrees(route), alphaFor(dt, config.tauSeconds), gap)
            source = HeadingSource.ROUTE
            return estimate()
        }

        if (speed >= config.stoppedSpeedMps) {
            ridingTimeMs += dtMs
            val course = if (rawCourse == null) null else gateJump(rawCourse, speed, accuracy)

            if (speed >= config.fullCourseSpeedMps) {
                if (course != null) {
                    if (karoo != null) recordTrust(abs(normalizeSignedDegrees(karoo - course)))
                    val delta = if (heading.isNaN()) 0.0 else abs(normalizeSignedDegrees(course - heading))
                    apply(course, alphaFor(dt, tauForDelta(delta)), gap)
                    source = HeadingSource.GPS_COURSE
                } else if (!heading.isNaN()) {
                    source = HeadingSource.HELD
                }
            } else {
                // 1..3 m/s: complementary blend; HEADING weight 0 at 3 m/s, 1 at 1 m/s.
                val span = config.fullCourseSpeedMps - config.stoppedSpeedMps
                val wKaroo = ((config.fullCourseSpeedMps - speed) / span).coerceIn(0.0, 1.0)
                val target: Double?
                val src: HeadingSource
                if (course != null && karoo != null) {
                    target = normalizeDegrees(course + wKaroo * normalizeSignedDegrees(karoo - course))
                    src = HeadingSource.BLEND
                } else if (course != null) {
                    target = course
                    src = HeadingSource.GPS_COURSE
                } else if (karoo != null) {
                    target = karoo
                    src = HeadingSource.KAROO_HEADING
                } else {
                    target = null
                    src = HeadingSource.HELD
                }
                if (target != null) {
                    // Slower smoothing as speed falls: tau scales with fullCourseSpeed / speed.
                    val tau = config.tauSeconds * config.fullCourseSpeedMps / speed
                    apply(target, alphaFor(dt, tau), gap)
                }
                source = if (heading.isNaN()) HeadingSource.NONE else src
            }
        } else {
            pendingCourse = Double.NaN
            if (karoo != null && isKarooTrusted()) {
                apply(karoo, alphaFor(dt, config.tauSeconds), gap)
                source = HeadingSource.KAROO_HEADING
            } else if (!heading.isNaN()) {
                source = HeadingSource.HELD
            }
        }
        return estimate()
    }

    private fun estimate() = HeadingEstimate(currentHeadingDeg, if (heading.isNaN()) HeadingSource.NONE else source, isKarooTrusted())

    /** Returns the course to use, or null if this fix is rejected as an unconfirmed jump. */
    private fun gateJump(course: Double, speed: Double, accuracy: Double?): Double? {
        if (heading.isNaN()) return course
        val jump = abs(normalizeSignedDegrees(course - heading))
        val needsConfirm = jump > config.maxJumpDeg &&
            ((accuracy != null && accuracy > config.jumpConfirmAccuracyM) || speed < config.fullCourseSpeedMps)
        if (!needsConfirm) {
            pendingCourse = Double.NaN
            return course
        }
        val confirmed = !pendingCourse.isNaN() &&
            abs(normalizeSignedDegrees(course - pendingCourse)) <= config.maxJumpDeg / 2.0
        if (confirmed) {
            pendingCourse = Double.NaN
            return course
        }
        pendingCourse = course
        return null
    }

    private fun tauForDelta(delta: Double): Double {
        val f = ((delta - config.fastTurnStartDeg) / (config.fastTurnFullDeg - config.fastTurnStartDeg)).coerceIn(0.0, 1.0)
        return config.tauSeconds + f * (config.turnTauSeconds - config.tauSeconds)
    }

    private fun alphaFor(dt: Double, tau: Double): Double = if (dt <= 0.0) 1.0 else 1.0 - exp(-dt / tau)

    private fun apply(target: Double, alpha: Double, gap: Boolean) {
        heading = if (heading.isNaN() || gap) target else smoothAngle(heading, target, alpha)
    }

    private fun recordTrust(diff: Double) {
        trustTime[trustHead] = ridingTimeMs
        trustDiff[trustHead] = diff
        trustHead = (trustHead + 1) % trustCapacity
        if (trustCount < trustCapacity) trustCount++
    }

    private fun isKarooTrusted(): Boolean {
        var n = 0
        val since = ridingTimeMs - config.trustWindowMs
        for (k in 0 until trustCount) {
            val i = (trustHead - 1 - k + trustCapacity) % trustCapacity
            if (trustTime[i] < since) break
            if (trustDiff[i] > config.trustToleranceDeg) return false
            n++
        }
        return n >= config.trustMinSamples
    }
}
