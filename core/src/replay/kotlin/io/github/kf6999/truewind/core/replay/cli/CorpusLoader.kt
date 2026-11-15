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

package io.github.kf6999.truewind.core.replay.cli

import com.garmin.fit.Decode
import com.garmin.fit.MesgBroadcaster
import com.garmin.fit.RecordMesg
import com.garmin.fit.UserProfileMesg
import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.replay.ForecastArchive
import io.github.kf6999.truewind.core.replay.Geo
import io.github.kf6999.truewind.core.replay.ObservationArchive
import io.github.kf6999.truewind.core.replay.ReplayCorpus
import io.github.kf6999.truewind.core.replay.Resolution
import io.github.kf6999.truewind.core.replay.Ride
import io.github.kf6999.truewind.core.replay.RideSample
import io.github.kf6999.truewind.core.replay.RideTruth
import io.github.kf6999.truewind.core.replay.StationInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.InputStream
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream

/**
 * Loads a corpus directory (format: `corpus/README.md`). A directory containing `rides/` is one
 * corpus; otherwise every immediate subdirectory containing `rides/` is loaded and merged
 * (archives are keyed by location, so merging corpora from different places is safe).
 */
object CorpusLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun load(root: File): ReplayCorpus = load(listOf(root))

    fun load(roots: List<File>): ReplayCorpus {
        val dirs = roots.flatMap { root ->
            if (File(root, "rides").isDirectory) listOf(root) else
                root.listFiles().orEmpty().filter { it.isDirectory && File(it, "rides").isDirectory }.sortedBy { it.name }
        }.distinct()
        require(dirs.isNotEmpty()) { "no corpus (a directory with rides/) found under $roots" }
        val rides = mutableListOf<Ride>()
        val series = mutableMapOf<Pair<String, Resolution>, MutableList<ForecastSeries>>()
        val obs = mutableListOf<Observation>()
        for (dir in dirs) {
            rides += loadRides(File(dir, "rides"))
            File(dir, "forecasts").listFiles().orEmpty().filter { it.name.endsWith(".json") || it.name.endsWith(".json.gz") }.sortedBy { it.name }.forEach { f ->
                parseOpenMeteo(readText(f), defaultModel = f.name.substringBefore('.')).forEach { (k, v) -> series.getOrPut(k) { mutableListOf() } += v }
            }
            File(dir, "observations").listFiles().orEmpty().filter { it.name.endsWith(".csv") || it.name.endsWith(".csv.gz") }.sortedBy { it.name }.forEach { f ->
                obs += parseObservationsCsv(readText(f))
            }
        }
        return ReplayCorpus(
            name = dirs.joinToString("+") { it.name },
            rides = rides.sortedBy { it.id },
            forecasts = ForecastArchive(series),
            observations = ObservationArchive(obs.distinctBy { Triple(it.stationId, it.timeMs, it.source) }),
        )
    }

    private fun readText(f: File): String = open(f).bufferedReader().use { it.readText() }

    private fun open(f: File): InputStream = if (f.name.endsWith(".gz")) GZIPInputStream(f.inputStream()) else f.inputStream()

    /* ------------------------------------------------------------------ rides */

    fun loadRides(dir: File): List<Ride> = dir.listFiles().orEmpty().sortedBy { it.name }.mapNotNull { f ->
        when {
            f.name.endsWith(".fit", ignoreCase = true) -> readFit(f)
            f.name.endsWith(".csv") || f.name.endsWith(".csv.gz") -> readRideCsv(f)
            else -> null
        }
    }.filter { it.samples.size >= 2 }

    /** Ride CSV: see `corpus/README.md`. Optional sidecar `<name>.json` with mass and truth parameters. */
    fun readRideCsv(f: File): Ride {
        val id = f.name.removeSuffix(".gz").removeSuffix(".csv")
        val lines = open(f).bufferedReader().readLines().filter { it.isNotBlank() && !it.startsWith("#") }
        val header = lines.first().split(',').map { it.trim() }
        val col = header.withIndex().associate { it.value to it.index }
        fun get(row: List<String>, name: String): Double? = col[name]?.let { row.getOrNull(it) }?.takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        val samples = mutableListOf<RideSample>()
        val truth = mutableListOf<WindVector>()
        for (line in lines.drop(1)) {
            val r = line.split(',')
            samples += RideSample(
                timeMs = get(r, "time_ms")!!.toLong(),
                lat = get(r, "lat")!!, lon = get(r, "lon")!!,
                altitudeM = get(r, "alt_m"), speedMps = get(r, "speed_mps"), powerW = get(r, "power_w"),
                cadenceRpm = get(r, "cadence_rpm"), gpsBearingDeg = get(r, "bearing_deg"), gpsAccuracyM = get(r, "accuracy_m"),
                headingDeg = get(r, "heading_deg"), temperatureC = get(r, "temp_c"), gradePct = get(r, "grade_pct"),
            )
            val tu = get(r, "truth_u"); val tv = get(r, "truth_v")
            if (tu != null && tv != null) truth += WindVector(tu, tv)
        }
        val sidecar = File(f.parentFile, "$id.json").takeIf { it.isFile }?.let { json.parseToJsonElement(it.readText()).jsonObject }
        val mass = sidecar?.get("system_mass_kg")?.jsonPrimitive?.doubleOrNull
        val cda = sidecar?.get("truth_cda")?.jsonPrimitive?.doubleOrNull
        val crr = sidecar?.get("truth_crr")?.jsonPrimitive?.doubleOrNull
        return Ride(
            id = id, samples = samples, systemMassKg = mass,
            truth = if (truth.size == samples.size && cda != null && crr != null) RideTruth(truth, cda, crr) else null,
            source = f.name,
        )
    }

    /**
     * FIT activity via the Garmin FIT SDK. Karoo FIT records carry no GPS course, so the bearing is
     * derived from consecutive positions (what the Karoo's `LOC_BEARING` approximates). System mass
     * = user profile weight + 9 kg bike when the profile is present.
     */
    fun readFit(f: File): Ride {
        val samples = mutableListOf<RideSample>()
        var weight: Double? = null
        val broadcaster = MesgBroadcaster()
        broadcaster.addListener { m: RecordMesg ->
            val lat = m.positionLat ?: return@addListener
            val lon = m.positionLong ?: return@addListener
            val t = m.timestamp?.date?.time ?: return@addListener
            samples += RideSample(
                timeMs = t,
                lat = lat * SEMICIRCLE, lon = lon * SEMICIRCLE,
                altitudeM = (m.enhancedAltitude ?: m.altitude)?.toDouble(),
                speedMps = (m.enhancedSpeed ?: m.speed)?.toDouble(),
                powerW = m.power?.toDouble(),
                cadenceRpm = m.cadence?.toDouble(),
                temperatureC = m.temperature?.toDouble(),
                gradePct = m.grade?.toDouble(),
                gpsAccuracyM = 5.0,
            )
        }
        broadcaster.addListener { m: UserProfileMesg -> m.weight?.let { weight = it.toDouble() } }
        f.inputStream().buffered().use { Decode().read(it, broadcaster) }
        val sorted = samples.sortedBy { it.timeMs }.distinctBy { it.timeMs }
        val withBearing = sorted.mapIndexed { i, s ->
            if (i == 0) s else {
                val p = sorted[i - 1]
                val d = Geo.distanceKm(p.point, s.point) * 1000
                s.copy(gpsBearingDeg = if (d > 0.3) Geo.bearingDeg(p.point, s.point) else null)
            }
        }
        return Ride(id = f.name.removeSuffix(".fit"), samples = withBearing, systemMassKg = weight?.let { it + 9.0 }, source = f.name)
    }

    private const val SEMICIRCLE = 180.0 / 2147483648.0

    /* ------------------------------------------------------------------ forecasts */

    /**
     * Parses an Open-Meteo forecast / historical-forecast / previous-runs response (one object or a
     * list of objects for multi-coordinate requests). Wind keys may be model-suffixed
     * (`wind_speed_10m_ncep_hrrr_conus`); unsuffixed keys take [defaultModel]. Times may be unix
     * seconds or ISO local times with `utc_offset_seconds`. Speeds in km/h are converted.
     */
    fun parseOpenMeteo(text: String, defaultModel: String): Map<Pair<String, Resolution>, List<ForecastSeries>> {
        val root = json.parseToJsonElement(text)
        val objs = when (root) { is JsonArray -> root.map { it.jsonObject }; is JsonObject -> listOf(root); else -> emptyList() }
        val out = mutableMapOf<Pair<String, Resolution>, MutableList<ForecastSeries>>()
        for (o in objs) {
            if (o["error"]?.jsonPrimitive?.content == "true") continue
            val lat = o["latitude"]?.jsonPrimitive?.doubleOrNull ?: continue
            val lon = o["longitude"]?.jsonPrimitive?.doubleOrNull ?: continue
            val elev = o["elevation"]?.jsonPrimitive?.doubleOrNull
            val offsetS = o["utc_offset_seconds"]?.jsonPrimitive?.longOrNull ?: 0L
            for ((block, res) in listOf("hourly" to Resolution.HOURLY, "minutely_15" to Resolution.MINUTELY_15)) {
                val b = o[block] as? JsonObject ?: continue
                val units = o["${block}_units"] as? JsonObject
                val times = (b["time"] as? JsonArray)?.map { parseTime(it, offsetS) } ?: continue
                val speedKeys = b.keys.filter { it.startsWith("wind_speed_10m") && !it.contains("previous_day") }
                for (sk in speedKeys) {
                    val suffix = sk.removePrefix("wind_speed_10m").removePrefix("_")
                    val model = suffix.ifEmpty { defaultModel }
                    val dk = "wind_direction_10m" + (if (suffix.isEmpty()) "" else "_$suffix")
                    val gk = "wind_gusts_10m" + (if (suffix.isEmpty()) "" else "_$suffix")
                    val factor = if (units?.get(sk)?.jsonPrimitive?.content?.contains("km") == true) 1 / 3.6 else 1.0
                    val sp = (b[sk] as JsonArray).map { num(it)?.times(factor) }
                    val dir = (b[dk] as? JsonArray)?.map { num(it) } ?: continue
                    val gu = (b[gk] as? JsonArray)?.map { num(it)?.times(factor) }
                    val steps = times.indices.map { i ->
                        val s = sp.getOrNull(i); val d = dir.getOrNull(i)
                        ForecastStep(
                            timeMs = times[i],
                            wind10m = if (s != null && d != null) WindVector.fromSpeedDirection(s, d) else null,
                            gust10m = gu?.getOrNull(i),
                        )
                    }
                    if (steps.all { it.wind10m == null }) continue
                    out.getOrPut("open-meteo:$model" to res) { mutableListOf() } += ForecastSeries("open-meteo:$model", GeoPoint(lat, lon), elev, steps)
                }
            }
        }
        return out
    }

    private fun num(e: JsonElement): Double? = if (e is JsonNull) null else e.jsonPrimitive.doubleOrNull

    private fun parseTime(e: JsonElement, offsetS: Long): Long {
        val p = e.jsonPrimitive
        p.longOrNull?.let { return it * 1000 }
        return LocalDateTime.parse(p.content).toEpochSecond(ZoneOffset.UTC).minus(offsetS) * 1000
    }

    /* ------------------------------------------------------------------ observations */

    /** Normalised observation CSV: `station,source,lat,lon,elev_m,time_ms,speed_mps,dir_deg,gust_mps,sensor_height_m`. */
    fun parseObservationsCsv(text: String): List<Observation> {
        val lines = text.lines().filter { it.isNotBlank() && !it.startsWith("#") }
        if (lines.isEmpty()) return emptyList()
        val col = lines.first().split(',').map { it.trim() }.withIndex().associate { it.value to it.index }
        return lines.drop(1).mapNotNull { line ->
            val r = line.split(',')
            fun s(n: String) = col[n]?.let { r.getOrNull(it) }?.trim()?.takeIf { it.isNotEmpty() && it != "M" }
            fun d(n: String) = s(n)?.toDoubleOrNull()
            val speed = d("speed_mps")
            val dir = d("dir_deg")
            Observation(
                stationId = s("station") ?: return@mapNotNull null,
                source = s("source") ?: "iem",
                location = GeoPoint(d("lat") ?: return@mapNotNull null, d("lon") ?: return@mapNotNull null),
                elevationM = d("elev_m"),
                timeMs = s("time_ms")?.toLongOrNull() ?: return@mapNotNull null,
                wind = if (speed != null && dir != null) WindVector.fromSpeedDirection(speed, dir) else if (speed == 0.0) WindVector.ZERO else null,
                speed = speed,
                gust = d("gust_mps"),
                variableDirection = speed != null && speed > 0 && dir == null,
                sensorHeightM = d("sensor_height_m") ?: 10.0,
            )
        }
    }

    fun stationsOf(obs: List<Observation>): List<StationInfo> = obs.groupBy { it.stationId }.map { (id, l) -> StationInfo(id, l.first().source, l.first().location, l.first().elevationM) }
}
