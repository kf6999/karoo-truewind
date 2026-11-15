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

package io.github.kf6999.truewind.core.engine

import io.github.kf6999.truewind.core.api.ForecastProvider
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.ForecastResult
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.fusion.FusionFixtures.HOME
import io.github.kf6999.truewind.core.fusion.FusionFixtures.MIN
import io.github.kf6999.truewind.core.fusion.FusionFixtures.T0
import io.github.kf6999.truewind.core.fusion.FusionFixtures.offset
import io.github.kf6999.truewind.core.fusion.FusionConfig
import io.github.kf6999.truewind.core.fusion.InMemoryKeyValueStore
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.math.lerp
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.obs.ObsFetchResult
import io.github.kf6999.truewind.core.virtualwind.RiderWindEstimator
import io.github.kf6999.truewind.core.virtualwind.RiderWindInputs
import kotlinx.coroutines.runBlocking
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WindEngineTest {
    private val step = 15 * MIN

    /** True wind: 5 m/s from the west, veering to 7 m/s from the north between +90 and +120 min. */
    private fun truth(t: Long): WindVector {
        val a = WindVector.fromSpeedDirection(5.0, 270.0)
        val b = WindVector.fromSpeedDirection(7.0, 0.0)
        val f = ((t - (T0 + 90 * MIN)).toDouble() / (30 * MIN)).coerceIn(0.0, 1.0)
        return lerp(a, b, f)
    }

    /** Fake ensemble: per-member wind functions, 15-min steps from −30 min to +4 h at every requested point. */
    private inner class FakeEnsemble(val members: Map<String, (Long) -> WindVector>) : ForecastProvider {
        override val id = "fake"
        val calls = ArrayList<Pair<Long, ForecastRequest>>()
        var failUntilMs = Long.MIN_VALUE
        var failFromMs = Long.MAX_VALUE
        var rateLimitAtOrAfterMs = Long.MAX_VALUE
        var rateLimited = false

        override suspend fun fetch(request: ForecastRequest): ForecastResult = respond(request).first

        fun respond(req: ForecastRequest): Pair<ForecastResult, SourceStatus> {
            val now = req.nowMs
            calls += now to req
            if (now in failFromMs until failUntilMs) return ForecastResult(emptyList()) to SourceStatus(error = "link down")
            if (!rateLimited && now >= rateLimitAtOrAfterMs) {
                rateLimited = true
                return ForecastResult(emptyList()) to SourceStatus(bytes = 120, httpStatus = 429, retryAfterSec = 300, error = "HTTP 429")
            }
            val ids = req.members.ifEmpty { members.keys.toList() }
            val start = now / step * step - 2 * step
            val series = req.points.flatMap { p ->
                ids.mapNotNull { id ->
                    val f = members[id] ?: return@mapNotNull null
                    ForecastSeries(id, p, 20.0, (0..18).map { i ->
                        val t = start + i * step
                        ForecastStep(t, f(t), gust10m = f(t).speed * 1.4, temperatureC = 12.0, relativeHumidityPct = 70.0,
                            surfacePressureHpa = if (i == 2) 1010.0 else null, weatherCode = if (i == 2) 3 else null)
                    })
                }
            }
            return ForecastResult(series, bytesReceived = 3_500) to SourceStatus(bytes = 3_500)
        }

        fun source() = ForecastSource(this) { respond(it) }
    }

    /** Fake station network along the route: a station every 8 km, 3 km north of the road. */
    private inner class FakeStations {
        val stations = (0..10).map { k -> "ST$k" to offset(HOME, 8.0 * k, 3.0) }
        var calls = 0
        var failFromMs = Long.MAX_VALUE
        var failUntilMs = Long.MIN_VALUE

        fun source() = ObservationSource("fake-obs") { center, radius ->
            calls++
            val now = currentMs
            if (now in failFromMs until failUntilMs) return@ObservationSource ObsFetchResult<Observation>(emptyList(), requests = 1, error = "link down")
            val obsTime = now - 3 * MIN
            val items = stations.filter { haversineKm(it.second, center) <= radius }.mapIndexed { i, (id, p) ->
                val noise = WindVector(0.3 * sin(i + now / 600_000.0), 0.3 * sin(2.0 * i + now / 900_000.0))
                val w = truth(obsTime) + noise
                Observation(id, "awc", p, null, obsTime, w, w.speed)
            }
            ObsFetchResult(items, requests = 1, bytesReceived = 1_500L * items.size)
        }
    }

    private var currentMs = T0

    private val members = mapOf<String, (Long) -> WindVector>(
        "fake:good" to { t -> truth(t) + WindVector(0.3, 0.2) },
        "fake:biased" to { t -> truth(t) + WindVector(3.0, -1.0) },
    )

    @Test
    fun threeHourRide_windShift_biasedMember_outage_and429() = runBlocking {
        val ensemble = FakeEnsemble(members)
        val obs = FakeStations()
        val store = InMemoryKeyValueStore()
        // SPEC 4.3 weighting dynamics (skill weighting is off in the tuned defaults).
        val spec = WindEngineConfig(fusion = FusionConfig.SPEC_4_3)
        val engine = WindEngine(listOf(ensemble.source()), listOf(obs.source()), store = store, config = spec)
        val estimator = RiderWindEstimator()

        assertEquals(Status.NO_DATA, engine.sample(T0, HOME).status)

        // Phone link drops (forecast and observations) from +60 to +95 min; a 429 at +150.
        ensemble.failFromMs = T0 + 60 * MIN; ensemble.failUntilMs = T0 + 95 * MIN
        obs.failFromMs = T0 + 60 * MIN; obs.failUntilMs = T0 + 95 * MIN
        ensemble.rateLimitAtOrAfterMs = T0 + 150 * MIN

        val cycles = ArrayList<Pair<Long, CycleReport>>()
        var sumErr = 0.0
        var n = 0
        var shiftErr = 0.0
        var shiftN = 0
        var biasedWeightAt30: Double? = null
        for (sec in 0..180 * 60 step 30) {
            val m = sec / 60L
            currentMs = T0 + sec * 1000L
            val rider = offset(HOME, 25.0 / 3600.0 * sec) // 25 km/h east
            val report = engine.runCycleIfDue(CycleContext(currentMs, rider, recording = true, visible = true))
            if (report.ran) {
                cycles += m to report
                assertTrue(report.budget.lastCycleRequests <= 8, "requests per cycle")
                assertTrue(report.budget.lastCycleBytes <= 60_000, "bytes per cycle")
                assertTrue(report.budget.lastHourBytes <= 400_000, "bytes per hour")
            }
            if (m == 30L && biasedWeightAt30 == null) biasedWeightAt30 = engine.memberWeights().toMap()["fake:biased"]

            val s = engine.sample(currentMs, rider)
            assertNotNull(s.model10m, "value at minute $m")
            assertEquals(Status.OK, s.status, "status at minute $m")
            val err = (s.model10m!! - truth(currentMs)).speed
            sumErr += err; n++
            if (m in 90..130) { shiftErr += err; shiftN++ }

            // The sample feeds the per-tick estimator unchanged.
            val out = estimator.update(RiderWindInputs(currentMs, s.model10m, s.gust10m, s.confidence, gpsCourseDeg = 90.0, speedMps = 6.9,
                temperatureC = s.weather?.temperatureC, relativeHumidityPct = s.weather?.relativeHumidityPct,
                modelSurfacePressureHpa = s.weather?.surfacePressureHpa, modelElevationM = s.weather?.gridElevationM))
            assertNotNull(out.rider)
        }

        // Weighting: the biased member is down-weighted quickly and stays down.
        assertTrue(biasedWeightAt30!! < 0.2, "biased weight at 30 min: $biasedWeightAt30")
        val finalWeights = engine.memberWeights().toMap()
        assertTrue(finalWeights.getValue("fake:good") > 0.85, "final weights $finalWeights")

        // Accuracy against truth, including through the shift and the outage.
        println("e2e: mean err ${"%.3f".format(sumErr / n)} m/s, shift err ${"%.3f".format(shiftErr / shiftN)}, " +
            "cycles ${cycles.size}, weights $finalWeights, budget ${cycles.last().second.budget}")
        assertTrue(sumErr / n < 0.8, "mean vector error ${sumErr / n}")
        assertTrue(shiftErr / shiftN < 1.0, "mean error during shift ${shiftErr / shiftN}")

        // Outage: failed attempts back off 1, 2, 4, 8 ... and recovery follows the link.
        val failed = cycles.filter { !it.second.success && it.first in 60..95 }.map { it.first }
        assertTrue(failed.size >= 3, "failed attempts $failed")
        val gaps = failed.zipWithNext { a, b -> b - a }
        assertEquals(listOf(1L, 2L, 4L, 8L).take(gaps.size), gaps.take(4), "backoff gaps $gaps")
        val recovered = cycles.first { it.first >= 95 && it.second.success }.first
        assertTrue(recovered <= 95 + 10, "recovered at $recovered")

        // 429 with Retry-After 300 s: no forecast request for 5 minutes afterwards.
        val limitedAt = ensemble.calls.first { it.first >= T0 + 150 * MIN }.first
        val nextCall = ensemble.calls.first { it.first > limitedAt }.first
        assertTrue(nextCall - limitedAt >= 5 * MIN, "next call ${(nextCall - limitedAt) / MIN} min after 429")

        // Station points ride along in the forecast request (rider + up to 3 stations).
        assertTrue(ensemble.calls.any { it.second.points.size == 4 })

        // Phone gone after the ride: values continue from cache for 4 h, then STALE.
        val lastFetch = cycles.last { it.second.success }.first * MIN + T0
        val end = offset(HOME, 75.0)
        assertEquals(Status.OK, engine.sample(lastFetch + 3 * 60 * MIN + 59 * MIN, end).status)
        val stale = engine.sample(lastFetch + 4 * 60 * MIN + 1 * MIN, end)
        assertEquals(Status.STALE, stale.status)
        assertNotNull(stale.model10m)
        assertTrue(stale.confidence <= 55, "confidence when stale ${stale.confidence}")

        // Weights persisted per cell: the next ride starts informed, before any observation.
        val obs2 = FakeStations().also { it.failFromMs = 0; it.failUntilMs = Long.MAX_VALUE }
        val next = WindEngine(listOf(FakeEnsemble(members).source()), listOf(obs2.source()), store = store, config = spec)
        currentMs = T0 + 24 * 60 * MIN
        next.runCycleIfDue(CycleContext(currentMs, offset(HOME, 75.0), recording = true, visible = false))
        val w = next.memberWeights().toMap()
        assertTrue(w.getValue("fake:biased") < 0.4, "persisted weights $w")
    }

    @Test
    fun upstreamEmulation_singleMemberHourlyLerpNoObservations() = runBlocking {
        // A member whose 15-min values zig-zag: hourly lerp must ignore the in-between steps.
        val zigzag: (Long) -> WindVector = { t ->
            val q = ((t - T0) / step).toInt()
            WindVector(if (Math.floorMod(q, 4) == 0) q.toDouble() else 100.0, 0.0)
        }
        val ensemble = FakeEnsemble(mapOf("open-meteo:best_match" to zigzag, "open-meteo:ecmwf_ifs" to { WindVector(50.0, 0.0) }))
        val obs = FakeStations()
        val engine = WindEngine(listOf(ensemble.source()), listOf(obs.source()), config = WindEngineConfig(upstreamEmulation = true))
        val ran = ArrayList<Long>()
        for (m in 0L..70L) {
            currentMs = T0 + m * MIN
            if (engine.runCycleIfDue(CycleContext(currentMs, HOME, recording = true, visible = false)).ran) ran += m
        }
        assertEquals(listOf(0L, 60L), ran)
        assertEquals(0, obs.calls)
        assertTrue(ensemble.calls.all { it.second.members == listOf("open-meteo:best_match") && it.second.points.size == 1 })
        // Fetched at +60: hourly steps at +60 (u=4) and +120 (u=8); at +75 the lerp gives 5.
        val s = engine.sample(T0 + 75 * MIN, HOME)
        assertEquals(5.0, s.model10m!!.u, 1e-9)
        assertEquals(listOf("open-meteo:best_match"), s.sources.models)
        assertNull(s.sources.nearestStationId)
    }

    @Test
    fun hourlySource_isScheduledAtMostHourly_andProvidersFailingGracefully() = runBlocking {
        val ensemble = FakeEnsemble(members)
        var hourlyCalls = 0
        val hourly = HourlySource("hourly") { hourlyCalls++; SourceStatus(bytes = 9_000) }
        val throwing = ObservationSource("broken") { _, _ -> error("boom") }
        val engine = WindEngine(listOf(ensemble.source()), listOf(throwing), hourly)
        var lastReport: CycleReport? = null
        for (m in 0L..70L) {
            currentMs = T0 + m * MIN
            engine.runCycleIfDue(CycleContext(currentMs, HOME, recording = true, visible = false)).let { if (it.ran) lastReport = it }
        }
        assertEquals(2, hourlyCalls)
        val r = lastReport!!
        assertTrue(r.success)
        assertEquals("boom", r.providers.first { it.providerId == "broken" }.error)
        assertEquals(Status.OK, engine.sample(currentMs, HOME).status)
        assertEquals(8L, r.budget.requestsByProvider["fake"])
    }
}
