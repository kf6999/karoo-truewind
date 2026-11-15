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

package io.github.kf6999.truewind.core.providers.wildfire

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.ObsFetchResult
import io.github.kf6999.truewind.core.providers.obs.arr
import io.github.kf6999.truewind.core.providers.obs.dbl
import io.github.kf6999.truewind.core.providers.obs.describeError
import io.github.kf6999.truewind.core.providers.obs.field
import io.github.kf6999.truewind.core.providers.obs.obj
import io.github.kf6999.truewind.core.providers.obs.parseJsonOrNull
import io.github.kf6999.truewind.core.providers.obs.retryAfterSec
import io.github.kf6999.truewind.core.providers.obs.safeGet
import io.github.kf6999.truewind.core.providers.obs.str
import io.github.kf6999.truewind.core.route.GeoMath
import kotlinx.serialization.json.JsonArray
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.cos

/** One polygon: [rings]`[0]` is the outer ring, the rest are holes. Rings are closed or open; both work. */
data class FirePolygon(val rings: List<List<GeoPoint>>)

/** An active wildfire perimeter, simplified. */
data class FirePerimeter(
    /** Stable key for "alert once" (incident name, or OBJECTID when the name is missing). */
    val id: String,
    val name: String,
    val acres: Double? = null,
    val percentContained: Double? = null,
    val polygons: List<FirePolygon>,
)

/**
 * NIFC WFIGS **current** interagency fire perimeters (US), keyless ArcGIS FeatureServer (WP13).
 *
 * Endpoint verified live 2026-11-15:
 * `https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query`
 * (layer "Perimeters", esriGeometryPolygon, maxRecordCount 2000, 47 active perimeters that day,
 * edited within the hour). Query by envelope with `outSR=4326`, `f=geojson`, minimal `outFields`,
 * `geometryPrecision=3` (~100 m) and `maxAllowableOffset` in degrees (server-side generalisation).
 *
 * Measured sizes (uncompressed / gzip on the wire): 0.6°x0.5° box around the 102,000-acre Aspen
 * Acres fire (CO) at offset 0.01: 17.9 KB / 2.3 KB; Grasshopper + Austin (OR) 19.2 KB / 3.0 KB;
 * Timber fire (Big Sur) 1.7 KB / 0.5 KB; empty box 98 B. Big fires have hundreds of tiny
 * unburned-island rings, so the uncompressed size can exceed the 15 KB budget; send
 * `Accept-Encoding: gzip`. Without simplification the Aspen Acres box was 932 KB.
 * Fields used: `poly_IncidentName`, `poly_GISAcres`, `attr_PercentContained`.
 * Non-US fires need NASA FIRMS (key required, not implemented).
 */
object NifcPerimeters {
    const val DEFAULT_URL =
        "https://services3.arcgis.com/T4QMspbfLg3qTGWY/arcgis/rest/services/WFIGS_Interagency_Perimeters_Current/FeatureServer/0/query"
    const val ATTRIBUTION = "Fire perimeters: NIFC WFIGS"

    fun buildUrl(
        center: GeoPoint,
        radiusKm: Double = 30.0,
        maxAllowableOffsetDeg: Double = 0.005,
        geometryPrecision: Int = 3,
        baseUrl: String = DEFAULT_URL,
    ): String {
        val dLat = radiusKm / KM_PER_DEGREE
        val dLon = radiusKm / (KM_PER_DEGREE * cos(Math.toRadians(center.lat)).coerceAtLeast(0.05))
        val env = listOf(center.lon - dLon, center.lat - dLat, center.lon + dLon, center.lat + dLat).joinToString("%2C") { fmt(it, 4) }
        return baseUrl +
            "?where=1%3D1" +
            "&geometry=$env&geometryType=esriGeometryEnvelope&inSR=4326&spatialRel=esriSpatialRelIntersects" +
            "&outFields=poly_IncidentName%2Cpoly_GISAcres%2Cattr_PercentContained" +
            "&returnGeometry=true&outSR=4326&geometryPrecision=$geometryPrecision" +
            "&maxAllowableOffset=${fmt(maxAllowableOffsetDeg, 4)}&f=geojson"
    }

    /** Fetches perimeters intersecting a box of +-[radiusKm] around [center]. Never throws. */
    suspend fun fetch(http: HttpClient, center: GeoPoint, radiusKm: Double = 30.0, baseUrl: String = DEFAULT_URL): ObsFetchResult<FirePerimeter> {
        val r = http.safeGet(buildUrl(center, radiusKm, baseUrl = baseUrl), mapOf("Accept-Encoding" to "gzip"))
        val bytes = r.body?.size?.toLong() ?: 0L
        if (!r.isSuccessful) return ObsFetchResult(emptyList(), 1, bytes, r.describeError(), r.statusCode.takeIf { it > 0 }, r.retryAfterSec())
        val parsed = parse(r.body) ?: return ObsFetchResult(emptyList(), 1, bytes, "unparseable response")
        return ObsFetchResult(parsed, 1, bytes)
    }

    /**
     * Parses a GeoJSON FeatureCollection (Polygon or MultiPolygon features). Returns null when the
     * body is not a FeatureCollection (e.g. an ArcGIS `{"error":...}` body). Rings with fewer
     * than 2 distinct points are dropped (2-point slivers still count for distance). Never throws.
     */
    fun parse(body: ByteArray?): List<FirePerimeter>? {
        val root = parseJsonOrNull(body).obj() ?: return null
        val features = root.field("features").arr() ?: return null
        return features.mapIndexedNotNull { i, f ->
            val o = f.obj() ?: return@mapIndexedNotNull null
            val props = o.field("properties").obj()
            val geom = o.field("geometry").obj() ?: return@mapIndexedNotNull null
            val coords = geom.field("coordinates").arr() ?: return@mapIndexedNotNull null
            val polygons = when (geom.field("type").str()) {
                "Polygon" -> listOfNotNull(polygon(coords))
                "MultiPolygon" -> coords.mapNotNull { (it as? JsonArray)?.let(::polygon) }
                else -> emptyList()
            }
            if (polygons.isEmpty()) return@mapIndexedNotNull null
            val name = props.field("poly_IncidentName").str()?.trim()?.takeIf { it.isNotEmpty() }
            FirePerimeter(
                id = name ?: (o.field("id").str() ?: "fire-$i"),
                name = name ?: "Wildfire",
                acres = props.field("poly_GISAcres").dbl(),
                percentContained = props.field("attr_PercentContained").dbl(),
                polygons = polygons,
            )
        }
    }

    private fun polygon(rings: JsonArray): FirePolygon? {
        val parsed = rings.mapNotNull { ring ->
            val pts = (ring as? JsonArray)?.mapNotNull { c ->
                val a = c as? JsonArray ?: return@mapNotNull null
                val lon = a.getOrNull(0).dbl() ?: return@mapNotNull null
                val lat = a.getOrNull(1).dbl() ?: return@mapNotNull null
                GeoPoint(lat, lon)
            }.orEmpty()
            pts.takeIf { it.distinct().size >= 2 }
        }
        return parsed.takeIf { it.isNotEmpty() }?.let { FirePolygon(it) }
    }

    private fun fmt(v: Double, decimals: Int) = BigDecimal.valueOf(v).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
}

/** Where the nearest perimeter is relative to the rider. */
data class FireProximity(
    val perimeter: FirePerimeter,
    /** 0 when the rider is inside the perimeter. */
    val distanceKm: Double,
    /** Bearing to the nearest edge point; null when inside. */
    val bearingDeg: Double?,
    val nearestPoint: GeoPoint,
    val inside: Boolean,
)

object FireDistance {
    /** Nearest perimeter to [rider], or null if there are none. */
    fun nearest(rider: GeoPoint, perimeters: List<FirePerimeter>): FireProximity? =
        perimeters.mapNotNull { proximity(rider, it) }.minByOrNull { it.distanceKm }

    fun proximity(rider: GeoPoint, perimeter: FirePerimeter): FireProximity? {
        val proj = GeoMath.LocalProjection(rider)
        var best = Double.MAX_VALUE
        var bestPoint: GeoPoint? = null
        var inside = false
        for (poly in perimeter.polygons) {
            if (containsOrigin(proj, poly)) inside = true
            for (ring in poly.rings) {
                for (k in ring.indices) {
                    val a = ring[k]
                    val b = ring[(k + 1) % ring.size]
                    val d = proj.distanceToSegmentKm(a, b)
                    if (d < best) { best = d; bestPoint = proj.closestOnSegment(a, b) }
                }
            }
        }
        val p = bestPoint ?: return null
        return FireProximity(
            perimeter = perimeter,
            distanceKm = if (inside) 0.0 else best,
            bearingDeg = if (inside || best < 0.01) null else GeoMath.bearingDeg(rider, p),
            nearestPoint = p,
            inside = inside,
        )
    }

    /** Even-odd test of the projection origin against all rings (outer ring and holes). */
    private fun containsOrigin(proj: GeoMath.LocalProjection, poly: FirePolygon): Boolean {
        var c = false
        for (ring in poly.rings) {
            val n = ring.size
            var j = n - 1
            for (i in 0 until n) {
                val xi = proj.x(ring[i]); val yi = proj.y(ring[i])
                val xj = proj.x(ring[j]); val yj = proj.y(ring[j])
                if ((yi > 0) != (yj > 0) && 0 < (xj - xi) * (0 - yi) / (yj - yi) + xi) c = !c
                j = i
            }
        }
        return c
    }
}
