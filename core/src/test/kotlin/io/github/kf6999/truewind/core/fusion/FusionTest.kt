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

import io.github.kf6999.truewind.core.fusion.FusionFixtures.HOME
import io.github.kf6999.truewind.core.fusion.FusionFixtures.MIN
import io.github.kf6999.truewind.core.fusion.FusionFixtures.T0
import io.github.kf6999.truewind.core.fusion.FusionFixtures.assertClose
import io.github.kf6999.truewind.core.fusion.FusionFixtures.obs
import io.github.kf6999.truewind.core.fusion.FusionFixtures.offset
import io.github.kf6999.truewind.core.fusion.FusionFixtures.series
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindVector
import kotlinx.coroutines.runBlocking
import kotlin.math.exp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FusionTest {
    private val equal = FusionConfig(priors = MemberPriors.EQUAL)

    private fun build(
        series: List<io.github.kf6999.truewind.core.api.ForecastSeries>,
        observations: List<io.github.kf6999.truewind.core.api.Observation> = emptyList(),
        config: FusionConfig = equal,
        weights: Map<String, Double>? = null,
    ): FusionSnapshot {
        val members = series.map { it.memberId }.distinct()
        val w = weights ?: MemberWeights(config).weightMap(members, T0)
        return assertNotNull(Fusion.build(T0, series, w, observations, config))
    }

    // ---- blend -------------------------------------------------------------------------------

    @Test
    fun identicalMembers_blendEqualsMember_andSpreadIsZero() {
        val wind = WindVector(3.0, -4.0)
        val snap = build(listOf("a", "b", "c").map { series(it, HOME) { wind } })
        val s = snap.sample(T0 + 7 * MIN, HOME)
        assertClose(3.0, s.model10m!!.u)
        assertClose(-4.0, s.model10m!!.v)
        assertClose(0.0, s.spread!!)
        assertEquals(Status.OK, s.status)
    }

    @Test
    fun opposingMembers_keepTheMeanSpeed_notCancelToCalm() {
        // 3 m/s from 270 and 3 m/s from 90 at equal weight: the vector mean is 0, the speed must stay 3.
        val snap = build(
            listOf(
                series("a", HOME) { WindVector.fromSpeedDirection(3.0, 270.0) },
                series("b", HOME) { WindVector.fromSpeedDirection(3.0, 90.0) },
            )
        )
        val s = snap.sample(T0, HOME)
        assertClose(3.0, s.model10m!!.speed, 1e-9)
        assertClose(3.0, s.spread!!, 1e-9)
    }

    @Test
    fun blendSpeedIsWeightedMeanOfMemberSpeeds_directionFromVectorMean() {
        // 4 m/s from north and 2 m/s from east, equal weight: mean speed 3, direction of the vector mean.
        val snap = build(
            listOf(
                series("a", HOME) { WindVector.fromSpeedDirection(4.0, 0.0) },
                series("b", HOME) { WindVector.fromSpeedDirection(2.0, 90.0) },
            )
        )
        val m = snap.sample(T0, HOME).model10m!!
        assertClose(3.0, m.speed, 1e-9)
        val vec = WindVector.fromSpeedDirection(4.0, 0.0) + WindVector.fromSpeedDirection(2.0, 90.0)
        assertClose(vec.directionFromDeg, m.directionFromDeg, 1e-6)
    }

    @Test
    fun timeInterpolationAcrossAReversal_keepsSpeed() {
        val snap = build(
            listOf(
                series("a", HOME, step = 60 * MIN) { t ->
                    WindVector.fromSpeedDirection(3.0, if (t <= T0) 270.0 else 90.0)
                }
            )
        )
        for (m in listOf(0L, 10L, 30L, 45L)) {
            val s = snap.sample(T0 + m * MIN, HOME).model10m!!.speed
            assertTrue(s > 2.9, "speed $s at +$m min")
        }
    }

    @Test
    fun explicitWeightsAreApplied() {
        val snap = build(
            listOf(series("a", HOME) { WindVector(10.0, 0.0) }, series("b", HOME) { WindVector(0.0, 0.0) }),
            weights = mapOf("a" to 0.8, "b" to 0.2),
        )
        assertClose(8.0, snap.sample(T0, HOME).model10m!!.u)
    }

    @Test
    fun timeInterpolationIsLinearBetween15MinSteps() {
        val snap = build(listOf(series("a", HOME) { t -> WindVector((t - T0).toDouble() / (15 * MIN) * 3.0, 0.0) }))
        assertClose(1.0, snap.sample(T0 + 5 * MIN, HOME).model10m!!.u, 1e-9)
        assertClose(4.0, snap.sample(T0 + 20 * MIN, HOME).model10m!!.u, 1e-9)
    }

    @Test
    fun memberMissingAtATime_isDroppedAndWeightsRenormalised() {
        val a = series("a", HOME) { WindVector(2.0, 0.0) }
        // b only covers the first hour (plus the 30-min hold).
        val b = series("b", HOME, to = T0 + 60 * MIN) { WindVector(4.0, 0.0) }
        val snap = build(listOf(a, b))
        assertClose(3.0, snap.sample(T0, HOME).model10m!!.u)
        assertClose(2.0, snap.sample(T0 + 3 * 60 * MIN, HOME).model10m!!.u)
    }

    @Test
    fun nearestForecastPointIsUsed() {
        val far = offset(HOME, 20.0)
        val snap = build(listOf(series("a", HOME) { WindVector(1.0, 0.0) }, series("a", far) { WindVector(5.0, 0.0) }))
        assertClose(1.0, snap.sample(T0, offset(HOME, 3.0)).model10m!!.u)
        assertClose(5.0, snap.sample(T0, offset(HOME, 17.0)).model10m!!.u)
    }

    @Test
    fun gustAndScalarsAreBlended_andSparseScalarsHeld() {
        val a = series("a", HOME, gust = { 8.0 }, temperature = { 10.0 }, pressure = { t -> if (t == T0) 1000.0 else null }) { WindVector(3.0, 0.0) }
        val b = series("b", HOME, gust = { 12.0 }, temperature = { 14.0 }) { WindVector(5.0, 0.0) }
        val s = build(listOf(a, b)).sample(T0 + 40 * MIN, HOME)
        assertClose(10.0, s.gust10m!!)
        assertClose(12.0, s.weather!!.temperatureC!!)
        assertClose(1000.0, s.weather!!.surfacePressureHpa!!)
    }

    // ---- weighting ---------------------------------------------------------------------------

    @Test
    fun noObservations_weightsEqualPriors() {
        val w = MemberWeights(FusionConfig.SPEC_4_3).weightMap(listOf("open-meteo:ncep_hrrr_conus", "open-meteo:gfs_seamless"), T0)
        assertClose(1.2 / 2.2, w.getValue("open-meteo:ncep_hrrr_conus"), 1e-9)
        assertClose(1.0 / 2.2, w.getValue("open-meteo:gfs_seamless"), 1e-9)
        // Tuned priors: global models are a fallback behind the regional ones, NBM in between.
        val members = listOf("open-meteo:ncep_hrrr_conus", "open-meteo:ncep_nbm_conus", "open-meteo:ecmwf_ifs", "open-meteo:gfs_global")
        val t = MemberWeights(FusionConfig()).weightMap(members, T0)
        assertClose(1.2 / 1.7, t.getValue("open-meteo:ncep_hrrr_conus"), 1e-9)
        assertClose(0.3 / 1.7, t.getValue("open-meteo:ncep_nbm_conus"), 1e-9)
        assertClose(0.1 / 1.7, t.getValue("open-meteo:ecmwf_ifs"), 1e-9)
    }

    @Test
    fun floorIsRespectedAndWeightsSumToOne() {
        val w = MemberWeights.normaliseWithFloor(doubleArrayOf(100.0, 1.0, 0.1, 50.0), 0.05)
        assertClose(1.0, w.sum(), 1e-12)
        assertTrue(w.all { it >= 0.05 - 1e-12 }, w.joinToString())
        assertTrue(w[0] > w[3])
        assertEquals(0.25, MemberWeights.normaliseWithFloor(DoubleArray(4) { 1.0 }, 0.05)[2])
    }

    @Test
    fun biasedMember_losesWeightWithinThreeCycles() {
        val cfg = FusionConfig(priors = MemberPriors.EQUAL)
        val tracker = MemberWeights(cfg)
        val scored = HashMap<String, Long>()
        val truth = WindVector(4.0, 2.0)
        val stations = listOf(offset(HOME, 5.0), offset(HOME, -6.0, 4.0), offset(HOME, 2.0, -8.0))
        val history = ArrayList<Double>()
        for (cycle in 0 until 3) {
            val now = T0 + cycle * 10 * MIN
            val series = stations.flatMap { p ->
                listOf(series("good", p) { truth + WindVector(0.3, -0.2) }, series("biased", p) { truth + WindVector(3.0, 0.0) })
            }
            val obs = stations.mapIndexed { i, p -> obs("S$i", p, now - 3 * MIN, truth) }
            tracker.update(Fusion.scoreMembers(series, obs, scored, cfg), now)
            history += tracker.weightMap(listOf("good", "biased"), now).getValue("biased")
        }
        assertTrue(history.zipWithNext().all { (a, b) -> b <= a + 1e-9 }, "biased weight should not grow: $history")
        assertTrue(history.last() < 0.2, "biased weight after 3 cycles: $history")
    }

    @Test
    fun sameObservationIsScoredOnce() {
        val scored = HashMap<String, Long>()
        val s = listOf(series("a", HOME) { WindVector(1.0, 0.0) })
        val o = listOf(obs("KSQL", HOME, T0, WindVector(2.0, 0.0)))
        assertEquals(1, Fusion.scoreMembers(s, o, scored).size)
        assertEquals(0, Fusion.scoreMembers(s, o, scored).size)
        assertEquals(1, Fusion.scoreMembers(s, listOf(o[0].copy(timeMs = T0 + 20 * MIN)), scored).size)
    }

    @Test
    fun reliabilityScalesScoring() {
        val s = listOf(series("a", HOME) { WindVector(1.0, 0.0) })
        val e = Fusion.scoreMembers(s, listOf(obs("C1", HOME, T0, WindVector(2.0, 0.0), reliability = 0.4)), HashMap())
        assertClose(0.4, e.single().weight)
        assertClose(1.0, e.single().squaredError)
    }

    @Test
    fun ewmaHalfLife_oldErrorsFade() {
        val cfg = FusionConfig(priors = MemberPriors.EQUAL, priorPseudoWeight = 0.0)
        val t = MemberWeights(cfg)
        t.update(listOf(MemberError("a", 9.0, 1.0)), T0)
        t.update(listOf(MemberError("a", 1.0, 1.0)), T0 + 60 * MIN)
        // Old error has half the weight: (0.5*9 + 1) / 1.5
        assertClose(5.5 / 1.5, t.effectiveMse("a", T0 + 60 * MIN), 1e-9)
    }

    @Test
    fun persistenceRoundTrip_andCellKeys() = runBlocking {
        val store = InMemoryKeyValueStore()
        val ws = WeightStore(store)
        val t = MemberWeights(equal)
        t.update(listOf(MemberError("open-meteo:a", 9.0, 3.0), MemberError("open-meteo:b", 0.25, 3.0)), T0)
        ws.save(HOME, t.export())
        val mid = io.github.kf6999.truewind.core.model.GeoPoint(36.85, -121.8) // near a cell centre
        assertEquals(ws.cellKey(mid), ws.cellKey(offset(mid, 3.0, 2.0)))
        assertNotEquals(ws.cellKey(mid), ws.cellKey(offset(mid, 90.0)))

        val fresh = MemberWeights(equal)
        fresh.import(ws.load(HOME), T0 + 24 * 60 * MIN)
        val w = fresh.weightMap(listOf("open-meteo:a", "open-meteo:b"), T0 + 24 * 60 * MIN)
        assertTrue(w.getValue("open-meteo:b") > 0.6, "next ride starts informed: $w")
        // Stale persistence is ignored.
        val old = MemberWeights(equal)
        old.import(ws.load(HOME), T0 + 30L * 24 * 60 * MIN)
        assertClose(0.5, old.weightMap(listOf("open-meteo:a", "open-meteo:b"), T0).getValue("open-meteo:a"), 1e-9)
        assertTrue(MemberWeights.decode("garbage\nx\t1\t2").isEmpty())
    }

    // ---- residual correction -----------------------------------------------------------------

    @Test
    fun colocatedFreshObservation_pullsRiderToObservation() {
        val obsWind = WindVector(6.0, 1.0)
        val snap = build(listOf(series("a", HOME) { WindVector(3.0, 0.0) }), listOf(obs("K1", HOME, T0, obsWind)))
        val s = snap.sample(T0, HOME)
        assertClose(6.0, s.model10m!!.u, 1e-9)
        assertClose(1.0, s.model10m!!.v, 1e-9)
    }

    @Test
    fun distantOldObservation_nudgesByLessThan20Percent() {
        val stn = offset(HOME, 30.0)
        val snap = build(
            listOf(series("a", HOME) { WindVector(3.0, 0.0) }, series("a", stn) { WindVector(3.0, 0.0) }),
            listOf(obs("K1", stn, T0 - 30 * MIN, WindVector(7.0, 0.0))),
            FusionConfig.SPEC_4_3,
        )
        val s = snap.sample(T0, HOME)
        val moved = s.model10m!!.u - 3.0
        assertTrue(moved > 0.0 && moved < 0.2 * 4.0, "moved $moved of 4.0")
        assertClose(4.0 * exp(-2.0) * exp(-1.0), moved, 0.02)
    }

    @Test
    fun lowReliabilityStation_correctsLess() {
        val snap = build(listOf(series("a", HOME) { WindVector(3.0, 0.0) }), listOf(obs("CW1", HOME, T0, WindVector(5.0, 0.0), reliability = 0.3)))
        assertClose(3.6, snap.sample(T0, HOME).model10m!!.u, 1e-9)
    }

    @Test
    fun speedOnlyObservation_correctsMagnitudeAlongBlend() {
        val snap = build(listOf(series("a", HOME) { WindVector(3.0, 4.0) }), listOf(obs("V1", HOME, T0, null, speed = 10.0)))
        val s = snap.sample(T0, HOME)
        assertClose(10.0, s.model10m!!.speed, 1e-9)
        assertClose(WindVector(3.0, 4.0).directionFromDeg, s.model10m!!.directionFromDeg, 1e-6)
    }

    @Test
    fun correctionDecaysWithAge_betweenCycles() {
        val snap = build(listOf(series("a", HOME) { WindVector(3.0, 0.0) }), listOf(obs("K1", HOME, T0, WindVector(5.0, 0.0))))
        val now = snap.sample(T0, HOME).correction!!.u
        val later = snap.sample(T0 + 30 * MIN, HOME).correction!!.u
        assertClose(2.0, now, 1e-9)
        assertClose(2.0 * exp(-1.0), later, 1e-9)
    }

    // ---- confidence, sources, status ---------------------------------------------------------

    @Test
    fun confidenceDeductions() {
        val pair = listOf(series("a", HOME) { WindVector(4.0, 1.0) }, series("b", HOME) { WindVector(2.0, 1.0) })
        // Spread 1 m/s → −10; no obs → −15.
        val s = build(pair).sample(T0, HOME, SampleParams(intervalMs = 10 * MIN))
        assertClose(10.0, s.confidenceBreakdown.spread, 1e-9)
        assertClose(15.0, s.confidenceBreakdown.noObservation)
        assertEquals(75, s.confidence)
        assertEquals(ConfidenceBand.HIGH, s.band)

        // Data 25 min old with a 10 min interval → −15; total 60 → MEDIUM.
        val aged = build(pair).sample(T0 + 25 * MIN, HOME, SampleParams(intervalMs = 10 * MIN))
        assertClose(15.0, aged.confidenceBreakdown.age, 1e-9)
        assertEquals(60, aged.confidence)
        assertEquals(ConfidenceBand.MEDIUM, aged.band)

        // Age cap 30; fresh co-located obs with a 2 m/s residual: −20, no "no obs" deduction.
        val withObs = build(pair, listOf(obs("K1", HOME, T0, WindVector(5.0, 1.0)))).sample(T0, HOME)
        // Blend speed is now the mean of member speeds (3.18), not |mean vector| (3.16): residual ~1.98 m/s.
        assertClose(20.0, withObs.confidenceBreakdown.residual, 0.5)
        assertClose(0.0, withObs.confidenceBreakdown.noObservation)
        assertEquals(70, withObs.confidence)

        val old = build(pair).sample(T0 + 3 * 60 * MIN, HOME, SampleParams(intervalMs = 10 * MIN))
        assertClose(30.0, old.confidenceBreakdown.age)
        assertEquals(45, old.confidence)
        assertEquals(ConfidenceBand.LOW, ConfidenceBand.of(39))
    }

    @Test
    fun lightWind_flagsDirectionOnly() {
        val s = build(listOf(series("a", HOME) { WindVector(1.0, 0.5) })).sample(T0, HOME)
        assertFalse(s.directionReliable)
        assertEquals(85, s.confidence)
    }

    @Test
    fun sourceSummary_listsMembersAndNearestStation() {
        val stn = offset(HOME, 6.0)
        val snap = build(
            listOf(series("open-meteo:a", HOME) { WindVector(3.0, 0.0) }, series("open-meteo:b", HOME) { WindVector(3.0, 0.0) }),
            listOf(obs("KSQL", stn, T0 - 4 * MIN, WindVector(3.0, 0.0)), obs("KSFO", offset(HOME, 20.0), T0, WindVector(3.0, 0.0))),
            weights = mapOf("open-meteo:a" to 0.95, "open-meteo:b" to 0.05),
        )
        val s = snap.sample(T0, HOME)
        assertEquals(listOf("open-meteo:a"), s.sources.models)
        assertEquals("KSQL", s.sources.nearestStationId)
        assertClose(6.0, s.sources.nearestStationDistanceKm!!, 0.05)
        assertClose(4.0, s.sources.observationAgeMin!!, 1e-9)
    }

    @Test
    fun staleAfterCacheWindow_noDataAfterHold() {
        val snap = build(listOf(series("a", HOME, to = T0 + 4 * 60 * MIN) { WindVector(3.0, 0.0) }))
        assertEquals(Status.OK, snap.sample(T0 + 3 * 60 * MIN + 59 * MIN, HOME).status)
        val stale = snap.sample(T0 + 4 * 60 * MIN + 20 * MIN, HOME)
        assertEquals(Status.STALE, stale.status)
        assertNotNull(stale.model10m)
        val gone = snap.sample(T0 + 6 * 60 * MIN + 1, HOME)
        assertEquals(Status.NO_DATA, gone.status)
        assertNull(gone.model10m)
    }

    @Test
    fun noUsableSeries_buildReturnsNull() {
        assertNull(Fusion.build(T0, listOf(series("a", HOME) { null }), mapOf("a" to 1.0), emptyList()))
    }
}
