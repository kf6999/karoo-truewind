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
