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

package io.github.kf6999.truewind.core.fusion

import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.obs.AwcMetarProvider
import io.github.kf6999.truewind.core.providers.obs.SynopticProvider
import io.github.kf6999.truewind.core.providers.obs.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.ln

class ObsQcTest {
    private val rider = GeoPoint(40.0, -105.0)
    private val now = 1_800_000_000_000L
    private val min = 60_000L

    /** Station [km] east of the rider (1 deg lon at 40N is ~85.3 km). */
    private fun obs(
        id: String,
        speed: Double? = 3.0,
        dirFrom: Double = 270.0,
        km: Double = 5.0,
        ageMin: Double = 5.0,
        elevationM: Double? = 1600.0,
        source: String = "awc",
        heightM: Double = 10.0,
        reliability: Double = 1.0,
        variable: Boolean = false,
        northKm: Double = 0.0,
    ) = Observation(
        stationId = id,
        source = source,
        location = GeoPoint(rider.lat + northKm / 111.2, rider.lon + km / 85.29),
        elevationM = elevationM,
        timeMs = now - (ageMin * min).toLong(),
        wind = if (speed == null || variable) null else WindVector.fromSpeedDirection(speed, dirFrom),
        speed = speed,
        gust = speed?.let { it * 1.5 },
        variableDirection = variable,
        sensorHeightM = heightM,
        reliability = reliability,
    )

    private fun run(vararg o: Observation, riderElev: Double? = 1600.0, config: ObsQcConfig = ObsQcConfig()) =
        ObsQc.apply(o.toList(), rider, riderElev, now, config)

    private fun ObsQcResult.reasonOf(id: String) = rejected.single { it.observation.stationId == id }.reason

    @Test fun acceptsGoodObservation() {
        val r = run(obs("A"))
        assertEquals(listOf("A"), r.accepted.map { it.stationId })
        assertTrue(r.rejected.isEmpty())
    }

    @Test fun rejectsMissingWind() = assertEquals(ObsRejectReason.MISSING_WIND, run(obs("A", speed = null)).reasonOf("A"))

    @Test fun ageRule() {
        val r = run(obs("OK", ageMin = 29.9), obs("OLD", ageMin = 30.5), obs("FUT", ageMin = -10.0), obs("SKEW", ageMin = -2.0))
        assertEquals(setOf("OK", "SKEW"), r.accepted.map { it.stationId }.toSet())
        assertEquals(ObsRejectReason.STALE, r.reasonOf("OLD"))
        assertEquals(ObsRejectReason.FROM_FUTURE, r.reasonOf("FUT"))
    }

    @Test fun distanceRule() {
        val r = run(obs("NEAR", km = 29.0), obs("FAR", km = 31.0))
        assertEquals(listOf("NEAR"), r.accepted.map { it.stationId })
        assertEquals(ObsRejectReason.TOO_FAR, r.reasonOf("FAR"))
    }

    @Test fun elevationRule_onlyWhenBothKnown() {
        val r = run(obs("UP", elevationM = 1849.0), obs("HIGH", elevationM = 1851.0), obs("UNK", elevationM = null))
        assertEquals(setOf("UP", "UNK"), r.accepted.map { it.stationId }.toSet())
        assertEquals(ObsRejectReason.ELEVATION, r.reasonOf("HIGH"))
        // Rider elevation unknown: rule skipped.
        assertEquals(3, run(obs("UP", elevationM = 1849.0), obs("HIGH", elevationM = 5000.0), obs("UNK"), riderElev = null).accepted.size)
    }

    @Test fun speedSanity() {
        val r = run(obs("OK", speed = 39.9), obs("BAD", speed = 45.0, km = 3.0))
        assertEquals(listOf("OK"), r.accepted.map { it.stationId })
        assertEquals(ObsRejectReason.SPEED_IMPLAUSIBLE, r.reasonOf("BAD"))
    }

    @Test fun calmIsAccepted() {
        val calm = obs("CALM", speed = 0.0).copy(wind = WindVector.ZERO)
        assertEquals(listOf("CALM"), run(calm).accepted.map { it.stationId })
    }

    @Test fun neighbourOutlier_vector() {
        val neighbours = (1..5).map { obs("N$it", speed = 4.0 + 0.2 * it, dirFrom = 260.0 + 4 * it, km = 2.0 + it) }
        val outlier = obs("OUT", speed = 9.0, dirFrom = 90.0, km = 4.5) // opposite direction
        val r = ObsQc.apply(neighbours + outlier, rider, 1600.0, now)
        assertEquals(ObsRejectReason.OUTLIER, r.reasonOf("OUT"))
        assertEquals(5, r.accepted.size)
    }

    @Test fun neighbourOutlier_spreadFloorProtectsLightWinds() {
        val calmish = (1..4).map { obs("N$it", speed = 0.0, km = it.toDouble()).copy(wind = WindVector.ZERO) }
        val breeze = obs("B", speed = 4.0, km = 2.5)
        val r = ObsQc.apply(calmish + breeze, rider, 1600.0, now)
        assertEquals("4 m/s against calm neighbours is within 3 x 1.5 m/s", 5, r.accepted.size)
        val gale = obs("G", speed = 12.0, km = 2.5)
        assertEquals(ObsRejectReason.OUTLIER, ObsQc.apply(calmish + gale, rider, 1600.0, now).reasonOf("G"))
    }

    @Test fun neighbourOutlier_needsEnoughNeighbours() {
        val r = run(obs("A", speed = 2.0), obs("B", speed = 2.5), obs("C", speed = 25.0, dirFrom = 90.0))
        assertEquals(3, r.accepted.size)
    }

    @Test fun neighbourOutlier_variableDirectionComparesSpeed() {
        val ns = (1..4).map { obs("N$it", speed = 3.0 + 0.1 * it, km = it.toDouble()) }
        val vrb = obs("V", speed = 15.0, variable = true, km = 2.2)
        assertEquals(ObsRejectReason.OUTLIER, ObsQc.apply(ns + vrb, rider, 1600.0, now).reasonOf("V"))
        val vrbOk = obs("V2", speed = 3.0, variable = true, km = 2.2)
        assertEquals(5, ObsQc.apply(ns + vrbOk, rider, 1600.0, now).accepted.size)
    }

    @Test fun logLaw_ratioAndNormalisation() {
        assertEquals(1.0, ObsQc.logLawRatio(10.0, 10.0, 0.1), 1e-12)
        assertEquals(ln(10.0 / 0.1) / ln(3.0 / 0.1), ObsQc.logLawRatio(3.0, 10.0, 0.1), 1e-12)
        // SPEC 4.4 table: 10 m -> 1.2 m over z0 = 0.03 is 0.64, 0.1 is 0.54.
        assertEquals(0.64, ObsQc.logLawRatio(10.0, 1.2, 0.03), 0.005)
        assertEquals(0.54, ObsQc.logLawRatio(10.0, 1.2, 0.10), 0.005)
        assertTrue(ObsQc.logLawRatio(0.01, 10.0, 0.1).isFinite())

        val low = obs("L", speed = 3.0, heightM = 3.0)
        val n = ObsQc.normaliseTo10m(low, 0.1)
        val k = ln(100.0) / ln(30.0)
        assertEquals(3.0 * k, n.speed!!, 1e-9)
        assertEquals(3.0 * k, n.wind!!.speed, 1e-9)
        assertEquals(270.0, n.wind!!.directionFromDeg, 1e-9)
        assertEquals(4.5 * k, n.gust!!, 1e-9)
        assertEquals(10.0, n.sensorHeightM, 0.0)

        val accepted = run(low).accepted.single()
        assertEquals(3.0 * k, accepted.speed!!, 1e-9)
        assertEquals(3.0, run(low, config = ObsQcConfig(normaliseHeight = false)).accepted.single().speed!!, 0.0)
    }

    @Test fun dedupe_sameIdAcrossProviders() {
        val awc = obs("KDEN", source = "awc", ageMin = 8.0)
        val syn = obs("KDEN", source = "synoptic", ageMin = 3.0, heightM = 10.0)
        val nws = obs("kden", source = "nws", ageMin = 20.0)
        val r = run(awc, syn, nws)
        assertEquals(1, r.accepted.size)
        // AWC and Synoptic within 10 min: equal reliability, AWC preferred by source.
        assertEquals("awc", r.accepted.single().source)
        assertEquals(2, r.rejected.count { it.reason == ObsRejectReason.DUPLICATE })
    }

    @Test fun dedupe_newerWinsBeyondTolerance() {
        val awc = obs("KDEN", source = "awc", ageMin = 25.0)
        val syn = obs("KDEN", source = "synoptic", ageMin = 2.0)
        assertEquals("synoptic", run(awc, syn).accepted.single().source)
    }

    @Test fun dedupe_reliabilityBeatsSource() {
        val a = obs("X1", source = "awc", reliability = 0.4, ageMin = 5.0)
        val b = obs("X1", source = "nws", reliability = 1.0, ageMin = 6.0)
        assertEquals("nws", run(a, b).accepted.single().source)
    }

    @Test fun dedupe_colocatedDifferentIds() {
        val awc = obs("KASE", source = "awc", km = 5.0)
        val syn = obs("ASPEN-SYN", source = "synoptic", km = 5.1) // 100 m away, other provider
        val other = obs("CWOP1", source = "synoptic", km = 5.05) // same provider as syn: not a duplicate of it
        val r = ObsQc.deduplicate(listOf(awc, syn, other))
        assertEquals(setOf("KASE"), r.first.map { it.stationId }.toSet())
        assertEquals(2, r.second.size)
        val sameSource = ObsQc.deduplicate(listOf(obs("S1", source = "synoptic", km = 5.0), obs("S2", source = "synoptic", km = 5.05)))
        assertEquals(2, sameSource.first.size)
    }

    @Test fun acceptedSortedByDistance() {
        val r = run(obs("FAR", km = 20.0), obs("NEAR", km = 1.0), obs("MID", km = 10.0))
        assertEquals(listOf("NEAR", "MID", "FAR"), r.accepted.map { it.stationId })
    }

    @Test fun realFixtures_awcPlusSynopticAspen() {
        // Rider in Aspen at 2400 m, "now" 10:10Z on the fixture day.
        val aspen = GeoPoint(39.1911, -106.8175)
        val nowFixture = java.time.Instant.parse("2026-11-15T10:10:00Z").toEpochMilli()
        val awc = requireNotNull(AwcMetarProvider.parse(fixture("providers/obs/awc/metar_aspen_valley.json"))).map { it.observation }
        val syn = requireNotNull(SynopticProvider.parse(fixture("providers/obs/synoptic/latest_aspen_sensorvars.json"), aspen)).map { it.observation }
        val r = ObsQc.apply(awc + syn, aspen, 2400.0, nowFixture)
        val ids = r.accepted.map { it.stationId }
        assertEquals("KASE once, from AWC", 1, ids.count { it == "KASE" })
        assertEquals("awc", r.accepted.single { it.stationId == "KASE" }.source)
        assertTrue("CO100 1 km away is kept", "CO100" in ids)
        // KEGE: 51 km away and 400 m lower.
        assertTrue(r.rejected.any { it.observation.stationId == "KEGE" })
        // CO094 claims 1191 ft (363 m): rejected on elevation.
        assertEquals(ObsRejectReason.ELEVATION, r.reasonOf("CO094"))
        // Temperature-only stations never get through.
        assertTrue(r.rejected.filter { it.observation.stationId == "MACC2" }.all { it.reason == ObsRejectReason.MISSING_WIND })
    }
}
