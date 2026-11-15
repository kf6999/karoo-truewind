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

package io.github.kf6999.truewind.core.alerts.storm

import io.github.kf6999.truewind.core.alerts.nws.NwsAlert
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.AwcMetar
import io.github.kf6999.truewind.core.providers.obs.ThunderIndicator
import io.github.kf6999.truewind.core.providers.radar.DbzGrid
import io.github.kf6999.truewind.core.providers.radar.PixelPoint
import io.github.kf6999.truewind.core.providers.radar.TileMath
import io.github.kf6999.truewind.core.route.GeoMath
import kotlin.math.ceil
import kotlin.math.floor

/*
 * STORM DISTANCE IS AN ESTIMATE, NOT LIGHTNING DATA.
 *
 * No free, keyless lightning-strike feed exists (SPEC 9.1). TrueWind approximates "how far is the
 * thunderstorm" from three proxies: strong radar reflectivity cores (>= ~45 dBZ is the usual
 * threshold for convective cells that can produce lightning), METAR thunderstorm codes at nearby
 * airports, and active NWS severe-thunderstorm / tornado warnings for the rider's point. Fields and
 * alerts built from [StormEstimate] must show it as an estimate ("est."). A real strike feed can
 * be plugged in through [LightningProvider]; only then is [StormEstimate.isEstimate] false.
 */

/** One lightning strike from a real strike feed. */
data class LightningStrike(val location: GeoPoint, val timeMs: Long, val cloudToGround: Boolean? = null)

/**
 * A real lightning-strike source (Tomorrow.io premium, Weatherbit, Xweather, Blitzortung with
 * permission, ...). No implementation ships: there is no free keyless feed.
 */
interface LightningProvider {
    val id: String

    suspend fun strikes(center: GeoPoint, radiusKm: Double, sinceMs: Long): List<LightningStrike>
}

/** A connected cluster of radar pixels at or above the core threshold. */
data class RadarCore(
    /** Distance from the rider to the nearest pixel of the cluster, km. 0 if the rider is inside it. */
    val distanceKm: Double,
    /** Bearing from the rider to that pixel, degrees; null when [distanceKm] is ~0. */
    val bearingDeg: Double?,
    val nearestPoint: GeoPoint,
    val maxDbz: Int,
    val pixelCount: Int,
    val areaKm2: Double,
    val frameTimeMs: Long,
)

/**
 * Finds strong-reflectivity cores in a [DbzGrid] (WP14). Pixels >= [thresholdDbz] are grouped
 * with 8-connectivity; clusters smaller than [minClusterAreaKm2] (and never fewer than
 * [minClusterPixels] pixels) are rejected as speckle / clutter.
 */
class RadarCoreFinder(
    val thresholdDbz: Int = 45,
    val minClusterAreaKm2: Double = 4.0,
    val minClusterPixels: Int = 3,
) {
    /** All cores, nearest first. */
    fun findCores(grid: DbzGrid, rider: GeoPoint, maxDistanceKm: Double = Double.MAX_VALUE): List<RadarCore> {
        val w = grid.width
        val h = grid.height
        val mpp = TileMath.metresPerPixel(rider.lat, grid.zoom, grid.tileSize)
        val pxAreaKm2 = (mpp / 1000) * (mpp / 1000)
        val minPx = maxOf(minClusterPixels, ceil(minClusterAreaKm2 / pxAreaKm2).toInt())
        val thr = thresholdDbz.toFloat()
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val cores = mutableListOf<RadarCore>()
        // Rider in grid coordinates (unwrapped around the grid origin).
        val rp = TileMath.toPixel(rider, grid.zoom, grid.tileSize)
        val world = grid.tileSize.toDouble() * (1 shl grid.zoom)
        var rx = rp.x - grid.originPx.x
        if (rx < -world / 2) rx += world else if (rx > world / 2) rx -= world
        val ry = rp.y - grid.originPx.y
        for (start in 0 until w * h) {
            if (seen[start] || !(grid.dbz[start] >= thr)) continue
            var sp = 0
            stack[sp++] = start
            seen[start] = true
            var count = 0
            var maxDbz = Float.NEGATIVE_INFINITY
            var bestD2 = Double.MAX_VALUE
            var bestIdx = start
            while (sp > 0) {
                val i = stack[--sp]
                count++
                val v = grid.dbz[i]
                if (v > maxDbz) maxDbz = v
                val x = i % w
                val y = i / w
                // Pixel-space distance to the nearest point of the pixel square, for choosing the nearest pixel.
                val dx = maxOf(x - rx, 0.0, rx - (x + 1))
                val dy = maxOf(y - ry, 0.0, ry - (y + 1))
                val d2 = dx * dx + dy * dy
                if (d2 < bestD2) { bestD2 = d2; bestIdx = i }
                for (ny in maxOf(0, y - 1)..minOf(h - 1, y + 1)) {
                    for (nx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                        val j = ny * w + nx
                        if (!seen[j] && grid.dbz[j] >= thr) { seen[j] = true; stack[sp++] = j }
                    }
                }
            }
            if (count < minPx) continue
            val bx = bestIdx % w
            val by = bestIdx / w
            val inside = bestD2 == 0.0
            // Nearest point on the pixel square to the rider, then to geo.
            val npx = rx.coerceIn(bx.toDouble(), bx + 1.0)
            val npy = ry.coerceIn(by.toDouble(), by + 1.0)
            val nearest = TileMath.fromPixel(
                PixelPoint(grid.originPx.x + npx, grid.originPx.y + npy),
                grid.zoom, grid.tileSize,
            )
            val dist = if (inside) 0.0 else haversineKm(rider, nearest)
            if (dist > maxDistanceKm) continue
            cores += RadarCore(
                distanceKm = dist,
                bearingDeg = if (dist < 0.05) null else GeoMath.bearingDeg(rider, nearest),
                nearestPoint = nearest,
                maxDbz = floor(maxDbz.toDouble()).toInt(),
                pixelCount = count,
                areaKm2 = count * pxAreaKm2,
                frameTimeMs = grid.frameTimeMs,
            )
        }
        return cores.sortedBy { it.distanceKm }
    }

    fun nearestCore(grid: DbzGrid, rider: GeoPoint, maxDistanceKm: Double = Double.MAX_VALUE): RadarCore? =
        findCores(grid, rider, maxDistanceKm).firstOrNull()
}

/** METAR thunder evidence at a known station location. */
data class StationThunder(val stationId: String, val location: GeoPoint, val timeMs: Long, val indicator: ThunderIndicator) {
    companion object {
        /** Thunder-reporting stations from decoded METARs (newest report per station). */
        fun fromMetars(metars: List<AwcMetar>): List<StationThunder> =
            metars.groupBy { it.observation.stationId }
                .mapNotNull { (_, reports) -> reports.maxByOrNull { it.observation.timeMs } }
                .filter { it.hasThunderstorm }
                .map { StationThunder(it.observation.stationId, it.observation.location, it.observation.timeMs, it.thunder) }
    }
}

enum class StormBasis { RADAR_CORE, METAR_THUNDER, NWS_WARNING, LIGHTNING_STRIKES }

enum class StormConfidence { LOW, MEDIUM, HIGH }

/**
 * Estimated distance to the nearest thunderstorm.
 *
 * **Not strike data** unless [isEstimate] is false (only when a [LightningProvider] supplied
 * strikes). [distanceKm] is null when the only evidence is an NWS warning for the rider's area
 * (warning polygons are not parsed; the alert applies to the rider's point).
 */
data class StormEstimate(
    val distanceKm: Double?,
    val bearingDeg: Double?,
    val basis: Set<StormBasis>,
    val confidence: StormConfidence,
    val isEstimate: Boolean = true,
    /** Strongest reflectivity of the nearest radar core, if radar contributed. */
    val maxDbz: Int? = null,
    /** Station id, alert event or `radar` that set the distance. */
    val nearestSource: String? = null,
    val timeMs: Long,
) {
    /** Short label for a numeric field, e.g. `12 km est.`. */
    fun label(unitKm: Boolean = true): String {
        val d = distanceKm ?: return if (StormBasis.NWS_WARNING in basis) "warn" else "--"
        val v = if (unitKm) d else d / 1.609344
        val txt = if (v < 10) String.format(java.util.Locale.US, "%.1f", v) else Math.round(v).toString()
        return "$txt ${if (unitKm) "km" else "mi"}${if (isEstimate) " est." else ""}"
    }
}

/** Combines the storm proxies into one [StormEstimate] (WP14). Pure; time comes from the caller. */
class StormEstimator(
    val maxRadarAgeMs: Long = 30 * 60_000L,
    val maxMetarAgeMs: Long = 75 * 60_000L,
    val maxStrikeAgeMs: Long = 15 * 60_000L,
    /** Evidence beyond this distance is ignored, km. */
    val maxRangeKm: Double = 100.0,
    /** A METAR station or warning within this distance of a radar core corroborates it, km. */
    val corroborationKm: Double = 30.0,
) {
    /**
     * Distance band (km) from a reporting station in which the thunderstorm is, per METAR
     * conventions: at station 0–8 km (5 SM), VCTS 8–16 km (5–10 SM), LTG overhead/vicinity
     * 0–16 km, LTG DSNT 16–48 km (10–30 SM). The estimate uses the near edge of the band.
     */
    fun bandKm(t: ThunderIndicator): ClosedFloatingPointRange<Double> = when (t) {
        ThunderIndicator.AT_STATION -> 0.0..8.0
        ThunderIndicator.VICINITY -> 8.0..16.0
        ThunderIndicator.LIGHTNING -> 0.0..16.0
        ThunderIndicator.LIGHTNING_DISTANT -> 16.0..48.0
        ThunderIndicator.NONE -> 0.0..0.0
    }

    fun estimate(
        rider: GeoPoint,
        nowMs: Long,
        radarCore: RadarCore? = null,
        stations: List<StationThunder> = emptyList(),
        nwsAlerts: List<NwsAlert> = emptyList(),
        strikes: List<LightningStrike> = emptyList(),
    ): StormEstimate? {
        // Real strike data wins outright.
        val recentStrikes = strikes.filter { nowMs - it.timeMs in 0..maxStrikeAgeMs }
        if (recentStrikes.isNotEmpty()) {
            val s = recentStrikes.minBy { haversineKm(rider, it.location) }
            val d = haversineKm(rider, s.location)
            if (d <= maxRangeKm) {
                return StormEstimate(d, if (d < 0.05) null else GeoMath.bearingDeg(rider, s.location), setOf(StormBasis.LIGHTNING_STRIKES), StormConfidence.HIGH, isEstimate = false, nearestSource = "strikes", timeMs = nowMs)
            }
        }

        val core = radarCore?.takeIf { nowMs - it.frameTimeMs <= maxRadarAgeMs && it.distanceKm <= maxRangeKm }
        val stationCandidates = stations
            .filter { it.indicator != ThunderIndicator.NONE && nowMs - it.timeMs <= maxMetarAgeMs }
            .map { st ->
                val ds = haversineKm(rider, st.location)
                Triple(st, ds, maxOf(0.0, ds - bandKm(st.indicator).endInclusive))
            }
            .filter { it.third <= maxRangeKm }
        val warnings = nwsAlerts.filter { isStormWarning(it) && it.isActiveAt(nowMs) }

        if (core == null && stationCandidates.isEmpty() && warnings.isEmpty()) return null

        val basis = mutableSetOf<StormBasis>()
        if (core != null) basis += StormBasis.RADAR_CORE
        if (stationCandidates.isNotEmpty()) basis += StormBasis.METAR_THUNDER
        if (warnings.isNotEmpty()) basis += StormBasis.NWS_WARNING

        // Nearest candidate distance.
        val nearestStation = stationCandidates.minByOrNull { it.third }
        var distance: Double? = null
        var bearing: Double? = null
        var source: String? = null
        if (core != null) { distance = core.distanceKm; bearing = core.bearingDeg; source = "radar" }
        if (nearestStation != null && (distance == null || nearestStation.third < distance)) {
            distance = nearestStation.third
            bearing = if (nearestStation.second < 0.5) null else GeoMath.bearingDeg(rider, nearestStation.first.location)
            source = nearestStation.first.stationId
        }
        if (distance == null) source = warnings.first().event

        val corroboratedRadar = core != null && (
            warnings.isNotEmpty() ||
                stationCandidates.any { haversineKm(it.first.location, core.nearestPoint) <= corroborationKm + bandKm(it.first.indicator).endInclusive }
            )
        val strongStation = stationCandidates.any { it.first.indicator == ThunderIndicator.AT_STATION || it.first.indicator == ThunderIndicator.LIGHTNING }
        val confidence = when {
            corroboratedRadar -> StormConfidence.HIGH
            core != null && core.maxDbz >= 50 -> StormConfidence.MEDIUM
            warnings.isNotEmpty() -> StormConfidence.MEDIUM
            strongStation -> StormConfidence.MEDIUM
            else -> StormConfidence.LOW
        }
        return StormEstimate(distance, bearing, basis, confidence, isEstimate = true, maxDbz = core?.maxDbz, nearestSource = source, timeMs = nowMs)
    }

    companion object {
        /** NWS events that mean a thunderstorm is at or near the rider's point (warnings, not watches). */
        val STORM_WARNING_EVENTS = setOf("Severe Thunderstorm Warning", "Tornado Warning", "Extreme Wind Warning")

        fun isStormWarning(a: NwsAlert): Boolean = a.event in STORM_WARNING_EVENTS
    }
}
