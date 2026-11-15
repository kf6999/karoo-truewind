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

package io.github.kf6999.truewind.core.alerts

import io.github.kf6999.truewind.core.alerts.nws.AlertSeverity
import io.github.kf6999.truewind.core.alerts.nws.AlertUrgency
import io.github.kf6999.truewind.core.alerts.nws.NwsAlert
import io.github.kf6999.truewind.core.alerts.storm.StormBasis
import io.github.kf6999.truewind.core.alerts.storm.StormConfidence
import io.github.kf6999.truewind.core.alerts.storm.StormEstimate
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.wildfire.FirePerimeter
import io.github.kf6999.truewind.core.providers.wildfire.FireProximity
import io.github.kf6999.truewind.core.time.ManualClock
import java.time.ZoneOffset
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AlertEngineTest {
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % 900_000L) // aligned to 15 min
    private val clock = ManualClock(t0)
    private val min = 60_000L

    @BeforeTest
    fun utc() { AlertText.zone = ZoneOffset.UTC }

    /** 15-min precipitation steps from t0, given as mm per step. */
    private fun precip(vararg mm: Double) = mm.mapIndexed { i, v -> PrecipStep(t0 + i * 15 * min, 15, v) }

    /** Runs the engine every minute for [minutes], returning all alerts fired. */
    private fun run(engine: AlertEngine, minutes: Int, inputs: () -> AlertInputs): List<RideAlert> {
        val out = mutableListOf<RideAlert>()
        repeat(minutes) {
            out += engine.evaluate(inputs())
            clock.advanceBy(min)
        }
        return out
    }

    @Test
    fun rainStartFiresOnceWithMinutes() {
        val e = AlertEngine(clock)
        // Dry for 30 min, then 0.5 mm per 15 min.
        val series = precip(0.0, 0.0, 0.0, 0.5, 0.6, 0.5, 0.0, 0.0, 0.0, 0.0)
        val first = e.evaluate(AlertInputs(precip = series))
        assertEquals(1, first.size)
        assertEquals("Rain in 45 min", first[0].title)
        assertEquals(AlertRule.RAIN_START, first[0].rule)
        assertTrue(first[0].detail.startsWith("Light rain expected around"))
        // Same data, later minutes: no repeat.
        clock.advanceBy(5 * min)
        assertTrue(e.evaluate(AlertInputs(precip = series)).isEmpty())
    }

    @Test
    fun rainBeyondLookaheadDoesNotFire() {
        val e = AlertEngine(clock)
        assertTrue(e.evaluate(AlertInputs(precip = precip(0.0, 0.0, 0.0, 0.0, 0.5))).isEmpty(), "rain in 60 min > 45 min")
        assertTrue(e.evaluate(AlertInputs(precip = precip(0.1, 0.1, 0.1, 0.1))).isEmpty(), "0.4 mm/h is below the 0.8 mm/h threshold")
    }

    @Test
    fun rainyDayReplayFiresStartAndEndExactlyOnce() {
        // 3 h replay: dry 45 min, wet 60 min, dry after. Engine ticks every minute.
        val e = AlertEngine(clock)
        val series = precip(0.0, 0.0, 0.0, 0.4, 1.2, 0.8, 0.3, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val alerts = run(e, 180) { AlertInputs(precip = series) }
        assertEquals(listOf(AlertRule.RAIN_START, AlertRule.RAIN_END), alerts.map { it.rule })
        assertEquals("Rain in 45 min", alerts[0].title)
        // Rain starts at +45; the end (+105) is announced as soon as it is within 60 min: at +45.
        assertEquals("Rain ends in 60 min", alerts[1].title)
    }

    @Test
    fun rainStartRearmsAfterAWetSpellOrWhenForecastClears() {
        val e = AlertEngine(clock)
        val wetSoon = precip(0.0, 0.5, 0.5, 0.5, 0.5)
        assertEquals(1, e.evaluate(AlertInputs(precip = wetSoon)).size)
        // Forecast drops the rain: after 30 min of "no rain expected" the rule re-arms.
        val dry = precip(*DoubleArray(20))
        assertTrue(run(e, 31) { AlertInputs(precip = dry) }.isEmpty())
        val later = (0 until 20).map { i -> PrecipStep(t0 + i * 15 * min, 15, if (i == 4) 0.5 else 0.0) }
        val again = e.evaluate(AlertInputs(precip = later))
        assertEquals(listOf(AlertRule.RAIN_START), again.map { it.rule })
    }

    @Test
    fun rainEndNeedsConfirmedDryStretch() {
        val e = AlertEngine(clock)
        // Raining now; a single dry step at +15 then wet again: not an end. Dry from +45 for 30 min: end in 45 min.
        val series = precip(0.5, 0.0, 0.5, 0.0, 0.0, 0.0)
        val a = e.evaluate(AlertInputs(precip = series))
        assertEquals("Rain ends in 45 min", a.single().title)
        assertTrue(e.evaluate(AlertInputs(precip = series)).isEmpty())
    }

    @Test
    fun minuteStepsSharpenTiming() {
        val e = AlertEngine(clock)
        val s15 = precip(0.0, 0.0, 0.5, 0.5)
        val s1 = (0 until 60).map { i -> PrecipStep(t0 + i * min, 1, if (i >= 7) 0.05 else 0.0) } // 3 mm/h from +7
        val a = e.evaluate(AlertInputs(precip = mergePrecip(s15, s1)))
        assertEquals("Rain in 7 min", a.single().title)
    }

    @Test
    fun gustHysteresis() {
        val e = AlertEngine(clock, units = AlertUnits())
        assertEquals("Gusts 47 km/h", e.evaluate(AlertInputs(currentGustMs = 13.0)).single().title)
        // Hovering around the threshold does not re-fire.
        val hover = run(e, 30) { AlertInputs(currentGustMs = if (clock.nowMs() / min % 2 == 0L) 11.0 else 12.5) }
        assertTrue(hover.isEmpty())
        // Below threshold - hysteresis (10 m/s) for 15 min re-arms.
        assertTrue(run(e, 16) { AlertInputs(currentGustMs = 9.0) }.isEmpty())
        assertEquals(1, e.evaluate(AlertInputs(currentGustMs = 12.0)).size)
        assertEquals("Gusts 27 mph", AlertEngine(clock, units = AlertUnits(metricSpeed = false)).evaluate(AlertInputs(currentGustMs = 12.0)).single().title)
    }

    @Test
    fun windShiftWithinThirtyMinutesFiresOnce() {
        val e = AlertEngine(clock)
        fun step(m: Int, dir: Double, spd: Double = 6.0) = ForecastStep(t0 + m * min, WindVector.fromSpeedDirection(spd, dir))
        val series = listOf(step(0, 200.0), step(15, 210.0), step(30, 290.0), step(45, 300.0))
        val a = e.evaluate(AlertInputs(windSeries = series)).single()
        assertEquals("Wind shift in 30 min", a.title)
        assertTrue(a.detail.startsWith("S 22 km/h → W"), a.detail)
        clock.advanceBy(5 * min)
        assertTrue(e.evaluate(AlertInputs(windSeries = series)).isEmpty())
        // Light wind shifts are ignored.
        val light = listOf(step(0, 0.0, 2.0), step(15, 180.0, 2.0))
        assertTrue(AlertEngine(ManualClock(t0)).evaluate(AlertInputs(windSeries = light)).isEmpty())
    }

    @Test
    fun nwsSevereFiresOncePerId() {
        val e = AlertEngine(clock)
        fun alert(id: String, sev: AlertSeverity, event: String = "Severe Thunderstorm Warning", type: String = "Alert", status: String = "Actual") =
            NwsAlert(id, event, sev, AlertUrgency.IMMEDIATE, headline = "$event until 5 PM", endsMs = t0 + 3600_000, messageType = type, status = status)
        val list = listOf(
            alert("a", AlertSeverity.SEVERE),
            alert("b", AlertSeverity.MODERATE, "Wind Advisory"),
            alert("c", AlertSeverity.EXTREME, "Tornado Warning"),
            alert("d", AlertSeverity.EXTREME, "Tornado Warning", status = "Test"),
        )
        val fired = e.evaluate(AlertInputs(nwsAlerts = list))
        assertEquals(listOf("nws-c", "nws-a"), fired.map { it.id })
        assertEquals(AlertPriority.CRITICAL, fired[0].priority)
        assertTrue(e.evaluate(AlertInputs(nwsAlerts = list)).isEmpty())
        // An Update for the same event soon after is not re-announced; a new event is.
        clock.advanceBy(10 * min)
        val upd = e.evaluate(AlertInputs(nwsAlerts = list + alert("a2", AlertSeverity.SEVERE, type = "Update") + alert("e", AlertSeverity.SEVERE, "Flash Flood Warning")))
        assertEquals(listOf("nws-e"), upd.map { it.id })
        // Expired alerts never fire.
        clock.advanceBy(2 * 3600_000L)
        assertTrue(e.evaluate(AlertInputs(nwsAlerts = listOf(alert("z", AlertSeverity.SEVERE)))).isEmpty())
    }

    @Test
    fun stormWithinRangeWithHysteresis() {
        val e = AlertEngine(clock)
        fun est(d: Double?) = StormEstimate(d, 270.0, setOf(StormBasis.RADAR_CORE), StormConfidence.MEDIUM, timeMs = clock.nowMs())
        assertTrue(e.evaluate(AlertInputs(storm = est(25.0))).isEmpty())
        val a = e.evaluate(AlertInputs(storm = est(18.0))).single()
        assertEquals("Storm ~18 km W", a.title)
        assertTrue(a.detail.contains("not lightning data"))
        // 25 km is inside the hysteresis band (20 + 10): stays disarmed.
        assertTrue(run(e, 40) { AlertInputs(storm = est(if (clock.nowMs() / min % 2 == 0L) 25.0 else 19.0)) }.isEmpty())
        assertTrue(run(e, 21) { AlertInputs(storm = est(35.0)) }.isEmpty())
        assertEquals(1, e.evaluate(AlertInputs(storm = est(12.0))).size)
    }

    @Test
    fun wildfirePerPerimeterOnce() {
        val e = AlertEngine(clock)
        fun prox(id: String, d: Double) = FireProximity(FirePerimeter(id, id, percentContained = 40.0, polygons = emptyList()), d, 90.0, GeoPoint(0.0, 0.0), d == 0.0)
        val a = e.evaluate(AlertInputs(fires = listOf(prox("Alpha", 14.0), prox("Beta", 30.0))))
        assertEquals("Wildfire Alpha", a.single().title)
        assertEquals("14 km E, 40% contained", a.single().detail)
        assertTrue(e.evaluate(AlertInputs(fires = listOf(prox("Alpha", 16.0), prox("Beta", 19.0)))).isEmpty())
        assertEquals(listOf("Wildfire Beta"), e.evaluate(AlertInputs(fires = listOf(prox("Beta", 10.0)))).map { it.title })
        assertTrue(e.evaluate(AlertInputs(fires = listOf(prox("Alpha", 21.0)))).isEmpty())
        val again = e.evaluate(AlertInputs(fires = listOf(prox("Alpha", 0.0)))).single()
        assertEquals(AlertPriority.CRITICAL, again.priority)
    }

    @Test
    fun aqiUpwardCrossings() {
        val e = AlertEngine(clock)
        assertTrue(e.evaluate(AlertInputs(usAqi = 80)).isEmpty())
        assertEquals("Air quality: Unhealthy for sensitive groups", e.evaluate(AlertInputs(usAqi = 105)).single().title)
        assertTrue(e.evaluate(AlertInputs(usAqi = 98)).isEmpty())
        assertTrue(e.evaluate(AlertInputs(usAqi = 103)).isEmpty(), "not below 101 - 10: no re-alert")
        assertEquals("Air quality: Unhealthy", e.evaluate(AlertInputs(usAqi = 160)).single().title)
        assertTrue(e.evaluate(AlertInputs(usAqi = 85)).isEmpty())
        assertEquals(1, e.evaluate(AlertInputs(usAqi = 120)).size, "dropped clearly, crossing again alerts")
        assertEquals(listOf(0, 1, 2, 3, 4, 5, 5), listOf(0, 51, 101, 151, 201, 301, 500).map { UsAqi.category(it) })
    }

    @Test
    fun resetStartsFresh() {
        val e = AlertEngine(clock)
        assertEquals(1, e.evaluate(AlertInputs(currentGustMs = 15.0)).size)
        e.reset()
        assertEquals(1, e.evaluate(AlertInputs(currentGustMs = 15.0)).size)
    }
}
