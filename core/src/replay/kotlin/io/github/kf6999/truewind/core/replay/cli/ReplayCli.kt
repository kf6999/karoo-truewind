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

import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.replay.LayerSeams
import io.github.kf6999.truewind.core.replay.ReplayConfigs
import io.github.kf6999.truewind.core.replay.ReplayRunner
import io.github.kf6999.truewind.core.replay.ReportJson
import io.github.kf6999.truewind.core.replay.Resolution
import io.github.kf6999.truewind.core.replay.Ride
import io.github.kf6999.truewind.core.replay.trackPoints
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant
import java.util.Locale
import kotlin.system.exitProcess

private const val USAGE = """
TrueWind replay harness (docs/TESTING.md section 3)

  run (default)   --corpus DIR [--corpus DIR ...] --out metrics.json [--configs upstream,+ensemble,...]
                  [--summary summary.md] [--baseline docs/accuracy-baseline.json --max-regression 0.02]
                  [--obs-lag-min N] [--cda-fit calm20|ride] [--set fusion.residualDistanceScaleKm=25,...] (configs also: member:<model>)
  synthesize DIR  write the deterministic synthetic fixture corpus to DIR
  synthesize-ride DIR --center LAT,LON --start ISO8601 --minutes N --id NAME
                  add a ride to a downloaded corpus whose truth wind is its station observations
  describe        --corpus DIR   print bbox/time of every ride (input for tools/download)
  fit2csv IN.fit OUT.csv          convert a FIT ride to the corpus CSV ride format
  live-smoke [--point LAT,LON]    one live request per provider via the production core classes
                                  (keys from SYNOPTIC_TOKEN / TOMORROW_API_KEY env, never printed)
  fit-devfields IN.fit            list the developer field names in a FIT file (device smoke check)
"""

fun main(args: Array<String>) {
    val cmd = args.firstOrNull()?.takeIf { !it.startsWith("--") } ?: "run"
    val rest = if (args.firstOrNull() == cmd) args.drop(1) else args.toList()
    val opts = parseOpts(rest)
    try {
        when (cmd) {
            "run" -> exitProcess(run(opts))
            "synthesize" -> {
                val dir = File(opts.positional.firstOrNull() ?: error("synthesize needs a directory"))
                Synthesizer().write(dir)
                println("wrote synthetic corpus to $dir")
            }
            "synthesize-ride" -> synthesizeRide(opts)
            "describe" -> describe(opts)
            "fit2csv" -> fit2csv(File(opts.positional[0]), File(opts.positional[1]))
            "live-smoke" -> {
                val (lat, lon) = (opts.one("point") ?: "37.62,-122.37").split(',').map { it.toDouble() }
                exitProcess(if (LiveSmoke.run(GeoPoint(lat, lon)) == 0) 0 else 1)
            }
            "fit-devfields" -> println(ReportJson.write(CorpusLoader.developerFieldNames(File(opts.positional[0]))))
            "help", "--help" -> println(USAGE)
            else -> { System.err.println(USAGE); exitProcess(2) }
        }
    } catch (e: IllegalArgumentException) {
        System.err.println("error: ${e.message}\n$USAGE"); exitProcess(2)
    }
}

class Opts(val values: Map<String, List<String>>, val positional: List<String>) {
    fun one(name: String): String? = values[name]?.lastOrNull()
    fun all(name: String): List<String> = values[name].orEmpty()
}

fun parseOpts(args: List<String>): Opts {
    val values = mutableMapOf<String, MutableList<String>>()
    val positional = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val a = args[i]
        if (a.startsWith("--")) {
            val (k, v) = if ('=' in a) a.substring(2).substringBefore('=') to a.substringAfter('=') else a.substring(2) to args.getOrElse(i + 1) { "" }.also { i++ }
            values.getOrPut(k) { mutableListOf() } += v
        } else positional += a
        i++
    }
    return Opts(values, positional)
}

private fun run(opts: Opts): Int {
    val corpusDirs = opts.all("corpus").ifEmpty { listOf("corpus/fixtures") }.map { File(it) }
    val out = File(opts.one("out") ?: "build/replay/metrics.json")
    val configs = opts.one("configs")?.split(',')?.map { it.trim() }?.let { ReplayConfigs.byName(it) } ?: ReplayConfigs.all
    opts.all("set").flatMap { it.split(',') }.filter { it.isNotBlank() }.forEach { applySetting(it) }
    // Each corpus directory keeps its own archives (rides on different days share forecast points).
    val corpora = CorpusLoader.loadEach(corpusDirs)
    val obsLagMs = ((opts.one("obs-lag-min")?.toDouble() ?: 0.0) * 60_000).toLong()
    System.err.println("replay: ${corpora.size} corpora, ${corpora.sumOf { it.rides.size }} rides, members ${corpora.flatMap { it.forecasts.members }.distinct()}, obs lag ${obsLagMs / 60_000} min")
    val cdaFit = opts.one("cda-fit") ?: "calm20"
    require(cdaFit == "calm20" || cdaFit == "ride") { "--cda-fit calm20|ride" }
    val report = ReplayRunner(corpora, configs, obsLagMs = obsLagMs, cdaFit = cdaFit).run().toMutableMap()
    report["generated_at"] = Instant.now().toString()
    if (opts.all("set").isNotEmpty()) report["settings"] = opts.all("set")
    out.absoluteFile.parentFile.mkdirs()
    out.writeText(ReportJson.write(report) + "\n")
    System.err.println("replay: wrote $out (${"%.1f".format(Locale.ROOT, report["wall_time_s"] as Double)} s)")
    val summary = summaryMarkdown(report)
    println(summary)
    opts.one("summary")?.let { File(it).apply { absoluteFile.parentFile.mkdirs() }.writeText(summary + "\n") }

    val baseline = opts.one("baseline")?.let { File(it) }
    if (baseline != null && baseline.isFile) {
        val maxReg = opts.one("max-regression")?.toDouble() ?: 0.02
        val regressions = regressions(Json.parseToJsonElement(baseline.readText()), report, maxReg)
        if (regressions.isNotEmpty()) {
            System.err.println("replay: ${regressions.size} metric(s) regressed by more than ${maxReg * 100}% vs $baseline:")
            regressions.forEach { System.err.println("  $it") }
            return 1
        }
        System.err.println("replay: no regression vs $baseline")
    } else if (baseline != null) {
        System.err.println("replay: baseline $baseline not found; skipping regression check")
    }
    return 0
}

/**
 * Experiment overrides of the production defaults, `--set key=value[,key=value]` (tuning runs only;
 * the defaults in core are what ships).
 */
private fun applySetting(kv: String) {
    val key = kv.substringBefore('=').trim()
    val value = kv.substringAfter('=').trim()
    fun d() = value.toDouble()
    fun minutes() = (value.toDouble() * 60_000).toLong()
    val e = LayerSeams.engineConfig
    val f = e.fusion
    val q = e.qc
    val before = LayerSeams.engineConfig to LayerSeams.riderConfig
    LayerSeams.engineConfig = when (key) {
        "fusion.halfLifeMin" -> e.copy(fusion = f.copy(halfLifeMs = minutes()))
        "fusion.epsilon" -> e.copy(fusion = f.copy(epsilon = d()))
        "fusion.weightFloor" -> e.copy(fusion = f.copy(weightFloor = d()))
        "fusion.priorMse" -> e.copy(fusion = f.copy(priorMse = d()))
        "fusion.priorPseudoWeight" -> e.copy(fusion = f.copy(priorPseudoWeight = d()))
        "fusion.residualDistanceScaleKm" -> e.copy(fusion = f.copy(residualDistanceScaleKm = d()))
        "fusion.residualAgeScaleMin" -> e.copy(fusion = f.copy(residualAgeScaleMs = minutes()))
        "fusion.residualAverageMin" -> e.copy(fusion = f.copy(residualAverageMs = minutes()))
        "fusion.skillWeighting" -> e.copy(fusion = f.copy(skillWeighting = value.toBoolean()))
        "fusion.spec" -> e.copy(fusion = io.github.kf6999.truewind.core.fusion.FusionConfig.SPEC_4_3)
        "fusion.memberAnchorMin" -> e.copy(fusion = f.copy(memberAnchorStepMs = minutes()))
        "fusion.memberHoldMin" -> e.copy(fusion = f.copy(memberHoldMs = minutes()))
        "fusion.priors" -> e.copy(fusion = f.copy(priors = parsePriors(value)))
        "qc.maxAgeMin" -> e.copy(qc = q.copy(maxAgeMs = minutes()))
        "qc.maxDistanceKm" -> e.copy(qc = q.copy(maxDistanceKm = d()))
        "qc.maxElevationDiffM" -> e.copy(qc = q.copy(maxElevationDiffM = d()))
        "qc.outlierSigma" -> e.copy(qc = q.copy(outlierSigma = d()))
        "qc.minSpread" -> e.copy(qc = q.copy(minSpread = d()))
        "engine.stationsInForecast" -> e.copy(stationsInForecast = value.toInt())
        "engine.observationRadiusKm" -> e.copy(observationRadiusKm = d())
        "planner.refreshMin" -> e.copy(planner = e.planner.copy(refreshIntervalMs = minutes()))
        else -> e
    }
    val r = LayerSeams.riderConfig
    LayerSeams.riderConfig = when (key) {
        "rider.exposure" -> r.copy(exposure = io.github.kf6999.truewind.core.physics.Exposure.valueOf(value))
        "rider.heightM" -> r.copy(riderHeightM = d())
        "rider.cda" -> r.copy(cda = d())
        "rider.crr" -> r.copy(crr = d())
        "virtual.measurementSigma" -> r.copy(virtualWind = r.virtualWind.copy(measurementVariance = d() * d()))
        "virtual.windProcessPerMin" -> r.copy(virtualWind = r.virtualWind.copy(windProcessVariancePerMin = d()))
        "virtual.priorSigmaFull" -> r.copy(virtualWind = r.virtualWind.copy(priorSigmaAtFullConfidence = d()))
        else -> r
    }
    require(key in KNOWN_SETTINGS || before != (LayerSeams.engineConfig to LayerSeams.riderConfig)) { "unknown or no-op setting $kv" }
}

private val KNOWN_SETTINGS = setOf("fusion.priors")

/** `hrrr:1.2;nbm:1.0` → priors by case-insensitive substring of the member id (first match wins, else 1.0). */
private fun parsePriors(spec: String): io.github.kf6999.truewind.core.fusion.MemberPriors {
    val rules = spec.split(';').filter { it.contains(':') }.map { it.substringBefore(':').lowercase() to it.substringAfter(':').toDouble() }
    return io.github.kf6999.truewind.core.fusion.MemberPriors { id -> rules.firstOrNull { id.lowercase().contains(it.first) }?.second ?: 1.0 }
}

/** Lower-is-better metrics compared against the baseline (TESTING 3.6, last paragraph). */
private val GATED = listOf(
    "metric_a.overall.vector_rmse_ms", "metric_a.overall.speed_mae_ms", "metric_a.overall.direction_mae_deg",
    "metric_b.ve_rmse_m", "metric_b.headwind_forward_rmse_ms", "metric_c.stopped_jitter_deg", "metric_c.turn_lag_s",
)

private fun regressions(baselineJson: kotlinx.serialization.json.JsonElement, report: Map<String, Any?>, maxReg: Double): List<String> {
    fun toAny(e: kotlinx.serialization.json.JsonElement): Any? = when (e) {
        is kotlinx.serialization.json.JsonObject -> e.mapValues { toAny(it.value) }
        is kotlinx.serialization.json.JsonArray -> e.map { toAny(it) }
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> e.content.toDoubleOrNull() ?: e.content
    }
    val base = ReportJson.flatten((toAny(baselineJson) as? Map<*, *>)?.get("configs"))
    val now = ReportJson.flatten(report["configs"])
    val out = mutableListOf<String>()
    for ((k, b) in base) {
        if (GATED.none { k.endsWith(it) }) continue
        val n = now[k] ?: continue
        if (b > 0 && (n - b) / b > maxReg) out += "$k: ${"%.4f".format(Locale.ROOT, b)} -> ${"%.4f".format(Locale.ROOT, n)}"
    }
    return out
}

@Suppress("UNCHECKED_CAST")
fun summaryMarkdown(report: Map<String, Any?>): String {
    fun g(m: Any?, vararg path: String): Any? { var c = m; for (p in path) c = (c as? Map<*, *>)?.get(p); return c }
    fun n(v: Any?, d: Int = 2) = (v as? Number)?.let { String.format(Locale.ROOT, "%.${d}f", it.toDouble()) } ?: "–"
    fun pct(v: Any?) = (v as? Number)?.let { String.format(Locale.ROOT, "%+.0f%%", it.toDouble() * 100) } ?: "–"
    val corpus = report["corpus"] as Map<String, Any?>
    val sb = StringBuilder()
    sb.append("## TrueWind replay — ${corpus["name"]}\n\n")
    sb.append("${corpus["rides"]} rides (${corpus["rides_with_power"]} with power), ${n(corpus["ride_hours"], 1)} h, members ${corpus["forecast_members"]}, ")
    sb.append("${corpus["stations"]} stations, Metric A ${corpus["metric_a_stations"]} ride-stations / ${n(corpus["metric_a_station_hours"], 1)} station-hours, obs lag ${n(corpus["obs_lag_min"], 0)} min.\n\n")
    sb.append("| config | A vec RMSE m/s | A speed MAE | A dir MAE ° | A shift dir MAE ° | B VE RMSE m | B fwd RMSE m/s | C stopped jitter ° | C turn lag s | fetches | impl |\n")
    sb.append("|---|---|---|---|---|---|---|---|---|---|---|\n")
    val configs = report["configs"] as Map<String, Any?>
    val cmp = report["comparison_vs_upstream"] as? Map<String, Any?>
    for ((name, c) in configs) {
        if (g(c, "available") == false) { sb.append("| $name | unavailable: ${g(c, "reason")} |||||||||||\n"); continue }
        val rel = g(cmp, name, "metric_a_vector_rmse_rel")
        sb.append("| $name | ${n(g(c, "metric_a", "overall", "vector_rmse_ms"))}${if (rel != null) " (${pct(rel)})" else ""} | ${n(g(c, "metric_a", "overall", "speed_mae_ms"))} | ")
        sb.append("${n(g(c, "metric_a", "overall", "direction_mae_deg"), 0)} | ${n(g(c, "metric_a", "regimes", "shift", "direction_mae_deg"), 0)} | ")
        sb.append("${n(g(c, "metric_b", "ve_rmse_m"))} | ${n(g(c, "metric_b", "headwind_forward_rmse_ms"))} | ")
        sb.append("${n(g(c, "metric_c", "stopped_jitter_deg"), 1)} | ${n(g(c, "metric_c", "turn_lag_s"), 1)} | ${g(c, "cost", "fetch_requests")} | ${g(c, "implementation")} |\n")
    }
    // Metric A breakdowns: vector RMSE (n) per regime and per distance to the nearest remaining station.
    val available = configs.filter { g(it.value, "available") != false }
    val regimes = listOf("calm", "moderate", "strong", "shift")
    sb.append("\nMetric A vector RMSE m/s by regime (n pairs):\n\n| config | ${regimes.joinToString(" | ")} |\n|---|${regimes.joinToString("") { "---|" }}\n")
    for ((name, c) in available) {
        sb.append("| $name | ")
        sb.append(regimes.joinToString(" | ") { r -> "${n(g(c, "metric_a", "regimes", r, "vector_rmse_ms"))} (${g(c, "metric_a", "regimes", r, "n") ?: 0})" })
        sb.append(" |\n")
    }
    val buckets = available.values.flatMap { (g(it, "metric_a", "by_nearest_remaining_station") as? Map<String, Any?>)?.keys.orEmpty() }.distinct().sorted()
    if (buckets.isNotEmpty()) {
        sb.append("\nMetric A vector RMSE m/s by distance from the held-out station to the nearest remaining station (n pairs):\n\n")
        sb.append("| config | ${buckets.joinToString(" | ")} |\n|---|${buckets.joinToString("") { "---|" }}\n")
        for ((name, c) in available) {
            sb.append("| $name | ")
            sb.append(buckets.joinToString(" | ") { b -> "${n(g(c, "metric_a", "by_nearest_remaining_station", b, "vector_rmse_ms"))} (${g(c, "metric_a", "by_nearest_remaining_station", b, "n") ?: 0})" })
            sb.append(" |\n")
        }
    }
    val targets = g(cmp, "targets") as? Map<String, Any?>
    if (targets != null) {
        sb.append("\nTESTING 3.6 targets on this corpus (informational; the gate is the full corpus): ")
        sb.append(targets.entries.joinToString(", ") { "${it.key}=${it.value ?: "n/a"}" })
        sb.append("\n")
    }
    return sb.toString()
}

private fun describe(opts: Opts) {
    val dir = File(opts.one("corpus") ?: error("--corpus needed"))
    if (!File(dir, "rides").isDirectory) { println(ReportJson.write(CorpusLoader.loadRides(dir).map { describeRide(it) })); return }
    val c = CorpusLoader.load(dir)
    println(ReportJson.write(linkedMapOf(
        "rides" to c.rides.map { describeRide(it) },
        "forecast_members" to c.forecasts.members.associateWith { m -> Resolution.entries.filter { c.forecasts.has(m, it) }.map { it.name } },
        "stations" to c.observations.byStation.mapValues { (_, l) -> l.size },
    )))
}

fun describeRide(r: Ride): Map<String, Any?> {
    val pts = r.trackPoints()
    return linkedMapOf(
        "id" to r.id,
        "start" to Instant.ofEpochMilli(r.startMs).toString(),
        "end" to Instant.ofEpochMilli(r.endMs).toString(),
        "lat_min" to pts.minOf { it.lat }, "lat_max" to pts.maxOf { it.lat },
        "lon_min" to pts.minOf { it.lon }, "lon_max" to pts.maxOf { it.lon },
        "samples" to r.samples.size, "has_power" to r.hasPower,
    )
}

private fun synthesizeRide(opts: Opts) {
    val dir = File(opts.positional.firstOrNull() ?: error("synthesize-ride needs a corpus directory"))
    val (lat, lon) = (opts.one("center") ?: error("--center LAT,LON")).split(',').map { it.toDouble() }
    val start = Instant.parse(opts.one("start") ?: error("--start ISO8601")).toEpochMilli()
    val minutes = opts.one("minutes")?.toInt() ?: 120
    val id = opts.one("id") ?: "synthetic-ride"
    val obs = File(dir, "observations").listFiles().orEmpty().filter { it.name.endsWith(".csv") }.flatMap { CorpusLoader.parseObservationsCsv(it.readText()) }
    require(obs.isNotEmpty()) { "no observations in $dir/observations" }
    File(dir, "rides").mkdirs()
    Synthesizer().writeRideFromObservations(dir, id, GeoPoint(lat, lon), start, minutes, obs)
    println("wrote $dir/rides/$id.csv")
}

private fun fit2csv(input: File, output: File) {
    val ride = CorpusLoader.readFit(input)
    val sb = StringBuilder("time_ms,lat,lon,alt_m,speed_mps,power_w,cadence_rpm,bearing_deg,accuracy_m,heading_deg,temp_c,grade_pct\n")
    fun f(d: Double?) = d?.let { String.format(Locale.ROOT, "%.7g", it) } ?: ""
    for (s in ride.samples) {
        sb.append(s.timeMs).append(',').append(f(s.lat)).append(',').append(f(s.lon)).append(',').append(f(s.altitudeM)).append(',')
            .append(f(s.speedMps)).append(',').append(f(s.powerW)).append(',').append(f(s.cadenceRpm)).append(',').append(f(s.gpsBearingDeg)).append(',')
            .append(f(s.gpsAccuracyM)).append(',').append(f(s.headingDeg)).append(',').append(f(s.temperatureC)).append(',').append(f(s.gradePct)).append('\n')
    }
    output.absoluteFile.parentFile.mkdirs()
    output.writeText(sb.toString())
    System.err.println("fit2csv: ${ride.samples.size} samples, mass ${ride.systemMassKg ?: "unknown"} kg -> $output")
}
