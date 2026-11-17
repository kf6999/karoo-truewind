package de.timklge.karooheadwind.datatypes

import de.timklge.karooheadwind.pipeline.OfflineInfo
import de.timklge.karooheadwind.pipeline.WindExtras
import de.timklge.karooheadwind.pipeline.WindSnapshot
import io.github.kf6999.truewind.core.fusion.ConfidenceBand
import io.github.kf6999.truewind.core.model.SourceSummary
import io.github.kf6999.truewind.core.model.Status
import io.github.kf6999.truewind.core.model.WindState
import io.github.kf6999.truewind.core.model.WindVector
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FieldStatusTest {
    private fun snapshot(
        status: Status = Status.OK, ageMs: Long? = 120_000L, band: ConfidenceBand? = ConfidenceBand.HIGH,
        offline: OfflineInfo? = null, upstream: Boolean = false, heading: Double = 0.0, windFrom: Double = 0.0,
    ) = WindSnapshot(
        WindState(
            timeMs = 1_794_657_600_000L,
            model10m = WindVector.fromSpeedDirection(5.0, windFrom),
            rider = WindVector.fromSpeedDirection(4.0, windFrom),
            gustRider = 6.0, headwind = null, crosswind = null, relativeDirectionDeg = null, confidence = 80,
            sources = SourceSummary(), ageMs = ageMs, airDensity = 1.2, weather = null, status = status, riderHeadingDeg = heading,
        ),
        WindExtras(upstreamMode = upstream, band = band, offline = offline),
    )

    @Test
    fun currentData_hasNoBadge() = assertNull(FieldStatus.badge(snapshot()))

    @Test
    fun offlineWinsAndShowsCacheAge() {
        assertEquals("OFFLINE 2h", FieldStatus.badge(snapshot(status = Status.STALE, offline = OfflineInfo(150 * 60_000L, true))))
        assertEquals("OFFLINE 40m", FieldStatus.badge(snapshot(offline = OfflineInfo(40 * 60_000L, false))))
    }

    @Test
    fun staleShowsAge() = assertEquals("OLD 1h", FieldStatus.badge(snapshot(status = Status.STALE, ageMs = 95 * 60_000L)))

    @Test
    fun lowConfidence_onlyInTrueWindMode() {
        assertEquals("LOW CONF", FieldStatus.badge(snapshot(band = ConfidenceBand.LOW)))
        assertNull(FieldStatus.badge(snapshot(band = ConfidenceBand.LOW, upstream = true)))
        assertNull(FieldStatus.badge(snapshot(band = ConfidenceBand.MEDIUM)))
    }

    @Test
    fun arrowDecor_headwindSign() {
        // Riding north into a north wind: headwind; riding south: tailwind.
        assertEquals(4.0, HeadwindDirectionDataType.decorOf(snapshot(heading = 0.0, windFrom = 0.0)).headwindMs)
        assertEquals(-4.0, HeadwindDirectionDataType.decorOf(snapshot(heading = 180.0, windFrom = 0.0)).headwindMs)
    }

    @Test
    fun forecastLabels() {
        assertEquals("12-18", windLabel(12, 18))
        assertEquals("12", windLabel(12, 12))
        assertEquals("Rain 40%", rainLabel(0.0, 40, true))
        assertNull(rainLabel(0.3, 10, false))
        assertEquals("0.3 mm", rainLabel(0.3, null, false))
        assertEquals("0.05 in", rainLabel(0.05, null, true))
        assertNull(rainLabel(0.0, null, true))
    }
}

class RideWeatherModelTest {
    private fun snap(heading: Double, windFrom: Double, speed: Double = 5.0, rain: Int? = 25, tempC: Double? = 18.0, feels: Double? = 14.0) = WindSnapshot(
        WindState(
            timeMs = 0L, model10m = WindVector.fromSpeedDirection(speed, windFrom), rider = WindVector.fromSpeedDirection(speed, windFrom),
            gustRider = null, headwind = null, crosswind = null, relativeDirectionDeg = null, confidence = 80, sources = SourceSummary(),
            ageMs = 0L, airDensity = 1.2,
            weather = tempC?.let { io.github.kf6999.truewind.core.model.WeatherNow(0, it, 50, 0.0, null, 0.0, 1013.0, 1000.0, speed, windFrom, speed, 0, false, false, 0.0) },
            status = Status.OK, riderHeadingDeg = heading,
        ),
        WindExtras(upstreamMode = false, rainInMin = rain, feelsLikeC = feels),
    )

    @Test
    fun headTailCross() {
        assertEquals("18 head", RideWeatherDataType.model(snap(0.0, 0.0), de.timklge.karooheadwind.WindUnit.KILOMETERS_PER_HOUR, false)!!.windText)
        assertEquals("11 tail", RideWeatherDataType.model(snap(180.0, 0.0), de.timklge.karooheadwind.WindUnit.MILES_PER_HOUR, false)!!.windText)
        assertEquals("5 cross", RideWeatherDataType.model(snap(90.0, 0.0), de.timklge.karooheadwind.WindUnit.METERS_PER_SECOND, false)!!.windText)
    }

    @Test
    fun temperatureFeelsAndRain() {
        val m = RideWeatherDataType.model(snap(0.0, 0.0), de.timklge.karooheadwind.WindUnit.MILES_PER_HOUR, true)!!
        assertEquals("64°F", m.tempText)
        assertEquals("feels 57°", m.feelsText)
        assertEquals("Rain in 25 min", m.rainText)
        assertNull(RideWeatherDataType.model(snap(0.0, 0.0, feels = 17.0), de.timklge.karooheadwind.WindUnit.MILES_PER_HOUR, true)!!.feelsText)
        assertEquals("Dry 2 h", RideWeatherDataType.rainText(null))
        assertNull(RideWeatherDataType.rainText(WindExtras.RAIN_UNKNOWN))
        assertEquals("Rain now", RideWeatherDataType.rainText(0))
    }
}
