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

package io.github.kf6999.truewind.core.providers.openmeteo

import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.time.Clock

/** Inclusive latitude/longitude box. Does not handle boxes crossing the antimeridian (none needed). */
data class LatLonBox(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    operator fun contains(p: GeoPoint): Boolean = p.lat in minLat..maxLat && p.lon in minLon..maxLon
}

/** A region whose [members] (Open-Meteo model ids, priority order) apply to points inside [box]. */
data class ModelRegion(val id: String, val box: LatLonBox, val members: List<String>)

/**
 * Which Open-Meteo models to request where (SPEC 4.2). Data-driven: the default table below can be
 * replaced by a JSON asset via [fromJson].
 *
 * For a point, the member list is the concatenation of the members of every region whose box
 * contains it, in table order, de-duplicated and capped at [maxMembers]. Points outside every
 * region get [fallbackMembers]. Specific (national) regions come first so that the
 * highest-resolution model leads; the wide regions append the global members.
 *
 * ## Live findings, 2026-11-15 (api.open-meteo.com, recorded in test fixtures)
 *
 * - **Discontinued per the docs page:** `ncep_hrrr_conus` (2026-11-30, RRFS replaces it),
 *   `ncep_nam_conus` (2026-11-01), `dwd_icon_global` / `icon_global`, `dwd_icon_eu` / `icon_eu`,
 *   `dwd_icon_d2` / `icon_d2` (all 2026-11-30), `geosphere_arome_austria` (2026-11-04),
 *   `kma_*` (2026-03-31), `bom_access_global` (2025-06-26). Replacements that answer today:
 *   `dwd_icon_d2_native`, `dwd_icon_d2_ruc_native` (what `dwd_icon_seamless` currently selects in
 *   Germany), `dwd_icon_eu_native`, `dwd_icon_global_native`, `ncep_rrfs_conus`,
 *   `ncep_rrfs_north_america`.
 * - **How a dead model looks:** `ncep_nam_conus` (already past its date) still validates but returns
 *   all-null arrays; an unknown id fails the whole request with HTTP 400
 *   `Cannot initialize MultiDomains from invalid String value <id>`. Both are handled by
 *   [MemberAvailability] plus the provider's drop-and-retry, so HRRR's removal needs no code change.
 * - **Out-of-domain members:** in a multi-model request a member with no data at a location is
 *   silently omitted from that location's object (not null-filled). With a single model the whole
 *   request fails with 400 `No data is available for this location`. When only one member is left
 *   at a location, Open-Meteo drops the key suffix (`wind_speed_10m` instead of
 *   `wind_speed_10m_<model>`); the parser re-attributes it using [covers].
 * - **Duplicates:** `gfs_seamless` / `ncep_gfs_seamless` equals `ncep_hrrr_conus` exactly in CONUS,
 *   and `best_match` equals HRRR in CONUS and `ecmwf_ifs` near Sydney. The table therefore uses
 *   `gfs_global` (`ncep_gfs_global`, distinct from HRRR) and never pairs `best_match` with the
 *   model it would pick; [dropDuplicateMembers] removes any remaining exact duplicates at runtime.
 * - **15-minute data and `models=`:** `minutely_15` is honoured per member (each member gets its own
 *   values and key suffix) everywhere, including outside North America/Central Europe. HRRR, RRFS,
 *   AROME, ICON-D2, UKMO 2 km, KNMI, MetNo Nordic, MeteoSwiss and JMA MSM values at the full hour
 *   can differ from their hourly values, i.e. a native sub-hourly source. ECMWF IFS, GFS and NBM
 *   15-minute series stay within 0.2 m/s of linear interpolation between hours, so treat them as
 *   hourly data interpolated by the API.
 * - **Model domains are not lat/lon boxes** (Lambert/rotated grids). The boxes below are
 *   conservative inner boxes checked at their corners on 2026-11-15; e.g. ICON-D2 has no data at
 *   45.5N 2W and HRRR none at 24.5N 125W, so those corners are excluded.
 */
class ModelRegistry(
    val regions: List<ModelRegion>,
    val fallbackMembers: List<String> = listOf(BEST_MATCH, "ecmwf_ifs", "gfs_global"),
    /** Members with worldwide coverage; [covers] is always true for them. */
    val globalMembers: Set<String> = DEFAULT_GLOBAL_MEMBERS,
    val maxMembers: Int = 5,
) {
    /** Members to request for [point], priority order, at most [maxMembers]. */
    fun membersFor(point: GeoPoint): List<String> {
        val matched = regions.filter { point in it.box }.flatMap { it.members }.distinct()
        return (matched.ifEmpty { fallbackMembers }).take(maxMembers)
    }

    /** Members for a multi-point request: decided by the rider, i.e. the first point. */
    fun membersFor(points: List<GeoPoint>): List<String> =
        if (points.isEmpty()) fallbackMembers.take(maxMembers) else membersFor(points.first())

    /**
     * Whether [member] is expected to have data at [point]: global members always, regional ones
     * when a region listing them contains the point. Used to attribute unsuffixed keys.
     */
    fun covers(member: String, point: GeoPoint): Boolean =
        member in globalMembers || regions.any { member in it.members && point in it.box }

    companion object {
        const val BEST_MATCH = "best_match"

        val DEFAULT_GLOBAL_MEMBERS: Set<String> = setOf(
            BEST_MATCH, "ecmwf_ifs", "ecmwf_ifs025", "gfs_global", "ncep_gfs_global", "gfs_seamless", "ncep_gfs_seamless",
            "dwd_icon_global_native", "icon_seamless", "dwd_icon_seamless", "ukmo_global_deterministic_10km",
            "meteofrance_arpege_world", "cmc_gem_gdps", "jma_gsm",
        )

        private val GLOBAL_TAIL = listOf("ecmwf_ifs", "gfs_global")

        /** Default table (SPEC 4.2), verified live on 2026-11-15; see the class comment. */
        val DEFAULT = ModelRegistry(
            regions = listOf(
                ModelRegion("switzerland", LatLonBox(45.8, 47.8, 5.9, 10.5), listOf("meteoswiss_icon_ch1")),
                ModelRegion("benelux", LatLonBox(49.5, 53.6, 2.5, 7.2), listOf("knmi_harmonie_arome_netherlands")),
                ModelRegion("uk_ireland", LatLonBox(49.8, 59.5, -10.5, 2.0), listOf("ukmo_uk_deterministic_2km")),
                ModelRegion("france", LatLonBox(42.0, 51.0, -5.0, 8.5), listOf("meteofrance_arome_france_hd")),
                ModelRegion("central_europe", LatLonBox(46.0, 56.5, 0.0, 19.0), listOf("dwd_icon_d2_ruc_native")),
                ModelRegion("italy", LatLonBox(36.5, 47.0, 6.5, 18.5), listOf("italia_meteo_arpae_icon_2i")),
                ModelRegion("nordic", LatLonBox(54.5, 71.0, 4.0, 31.0), listOf("metno_nordic")),
                ModelRegion("europe", LatLonBox(35.0, 71.5, -25.0, 44.0), GLOBAL_TAIL + "dwd_icon_eu_native"),
                // HRRR first while it lasts (native 15-min); drop-and-retry removes it after 2026-11-30.
                ModelRegion(
                    "conus",
                    LatLonBox(25.0, 49.5, -124.5, -67.5),
                    listOf("ncep_hrrr_conus", "ncep_rrfs_conus", "ncep_nbm_conus") + GLOBAL_TAIL,
                ),
                ModelRegion("japan", LatLonBox(30.0, 45.5, 129.0, 146.0), listOf("jma_msm") + GLOBAL_TAIL),
            ),
        )

        /**
         * Parses a registry from JSON:
         * `{"maxMembers":5,"fallback":["best_match",...],"global":[...],"regions":[{"id":"conus","box":[minLat,maxLat,minLon,maxLon],"members":[...]}]}`.
         * Returns null when the JSON is unusable, so callers keep [DEFAULT].
         */
        fun fromJson(json: String): ModelRegistry? {
            val root = parseJsonOrNull(json).asObject() ?: return null
            val regions = root["regions"].asArray()?.mapNotNull { r ->
                val o = r.asObject() ?: return@mapNotNull null
                val id = o["id"].asString() ?: return@mapNotNull null
                val box = o["box"].asArray()?.mapNotNull { it.asDouble() }?.takeIf { it.size == 4 } ?: return@mapNotNull null
                val members = o["members"].asArray()?.mapNotNull { it.asString() }?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                ModelRegion(id, LatLonBox(box[0], box[1], box[2], box[3]), members)
            } ?: return null
            val fallback = root["fallback"].asArray()?.mapNotNull { it.asString() }?.takeIf { it.isNotEmpty() }
                ?: listOf(BEST_MATCH, "ecmwf_ifs", "gfs_global")
            val global = root["global"].asArray()?.mapNotNull { it.asString() }?.toSet() ?: DEFAULT_GLOBAL_MEMBERS
            val max = root["maxMembers"].asInt()?.takeIf { it > 0 } ?: 5
            return ModelRegistry(regions, fallback, global, max)
        }
    }
}

/**
 * The 24 h drop-and-retry bookkeeping of SPEC 4.2, as a pure class with an injected clock.
 *
 * A member that failed (all nulls, or named in an API error) is dropped for [dropDurationMs].
 * [filter] removes dropped members from a request; if nothing would be left it returns
 * `[best_match]` alone. State can be persisted with [snapshot] / [restore].
 */
class MemberAvailability(
    private val clock: Clock,
    val dropDurationMs: Long = 24L * 60 * 60 * 1000,
    val lastResort: String = ModelRegistry.BEST_MATCH,
) {
    private val droppedUntil = LinkedHashMap<String, Long>()

    fun markFailed(member: String) {
        droppedUntil[member] = clock.nowMs() + dropDurationMs
    }

    fun markFailed(members: Collection<String>) = members.forEach { markFailed(it) }

    fun isDropped(member: String): Boolean {
        val until = droppedUntil[member] ?: return false
        if (clock.nowMs() >= until) {
            droppedUntil.remove(member)
            return false
        }
        return true
    }

    /** [members] minus dropped ones, order kept; `[lastResort]` when that would be empty. */
    fun filter(members: List<String>): List<String> {
        val kept = members.filterNot { isDropped(it) }
        return kept.ifEmpty { listOf(lastResort) }
    }

    /** Member to drop-until time (epoch ms), for persistence. Expired entries are pruned. */
    fun snapshot(): Map<String, Long> {
        droppedUntil.keys.toList().forEach { isDropped(it) }
        return droppedUntil.toMap()
    }

    fun restore(state: Map<String, Long>) {
        droppedUntil.clear()
        droppedUntil.putAll(state)
        snapshot()
    }
}
