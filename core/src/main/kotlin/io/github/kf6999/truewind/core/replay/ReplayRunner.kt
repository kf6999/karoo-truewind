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

package io.github.kf6999.truewind.core.replay

import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.math.sqrt

/** Everything a replay needs, loaded from `corpus/` (see `corpus/README.md` for the on-disk format). */
class ReplayCorpus(
    val name: String,
    val rides: List<Ride>,
    val forecasts: ForecastArchive,
    val observations: ObservationArchive,
)

/**
 * Runs every configuration over every ride of a corpus and computes Metrics A, B and C
 * (TESTING 3.2–3.4). Results are plain maps so they serialise to `metrics.json` without a JSON
 * library in core.
 */
class ReplayRunner(
    private val corpus: ReplayCorpus,
    private val configs: List<ReplayConfig> = ReplayConfigs.all,
    private val stationRadiusKm: Double = 30.0,
    /** Length of the calm stretch used to fit CdA/Crr (TESTING 3.3). */
    private val calmFitWindowS: Int = 20 * 60,
) {
    private class RidePrep(
        val ride: Ride,
        val rho: DoubleArray,
        val accel: DoubleArray,
        val course: Array<Double?>,
        val model: PowerModel?,
        val measured: Array<Double?>?,
        val stations: List<StationInfo>,
    )

    private class ConfigAccumulator {
        val pairs = mutableListOf<WindPair>()
        val rides = mutableListOf<Map<String, Any?>>()
        var veSquares = 0.0; var veN = 0
        var fwdSquares = 0.0; var fwdN = 0
        val jitterMeans = mutableListOf<Pair<Double, Int>>()
        val jitterP95 = mutableListOf<Double>()
        val lags = mutableListOf<Pair<Double, Int>>()
        var fetches = 0
        var ticks = 0L
        var tickNanos = 0L
    }

    fun run(): Map<String, Any?> {
        val started = System.nanoTime()
        val preps = corpus.rides.filter { it.samples.size >= 2 }.map { prepare(it) }
        val results = linkedMapOf<String, Any?>()
        val summaries = linkedMapOf<String, Map<String, Any?>>()

        for (config in configs) {
            val reason = config.unavailableReason
            if (reason != null) {
                results[config.name] = linkedMapOf("available" to false, "reason" to reason)
                continue
            }
            val acc = ConfigAccumulator()
            for (prep in preps) runRide(config, prep, acc)
            val summary = summarize(config, acc)
            summaries[config.name] = summary
            results[config.name] = summary
        }

        return linkedMapOf(
            "schema" to 1,
            "corpus" to linkedMapOf(
                "name" to corpus.name,
                "rides" to corpus.rides.size,
                "ride_hours" to corpus.rides.sumOf { (it.endMs - it.startMs) / 3_600_000.0 },
                "forecast_members" to corpus.forecasts.members,
                "stations" to corpus.observations.stations.size,
            ),
            "configs" to results,
            "comparison_vs_upstream" to compare(summaries),
            "wall_time_s" to (System.nanoTime() - started) / 1e9,
        )
    }

    private fun prepare(ride: Ride): RidePrep {
        val s = ride.samples
        val rho = DoubleArray(s.size) { VirtualElevation.airDensity(s[it].altitudeM, s[it].temperatureC) }
        val accel = VirtualElevation.accelerations(s)
        val course = VirtualElevation.trueCourse(s)
        val mass = ride.systemMassKg ?: Ride.DEFAULT_SYSTEM_MASS_KG
        val hasAltitude = s.count { it.altitudeM != null } > s.size / 2
        val model = if (ride.hasPower && hasAltitude) {
            val (cda, crr) = VirtualElevation.fitCdaCrr(s, calmestWindowStart(ride), calmFitWindowS, mass, rho, accel)
            PowerModel(mass, cda, crr)
        } else null
        val measured = model?.let { VirtualElevation.measuredHeadwind(s, it, rho, accel) }
        val track = s.filterIndexed { i, _ -> i % 60 == 0 }.map { it.point } + s.last().point
        val stations = corpus.observations.stationsNear(track, stationRadiusKm).filter { st ->
            corpus.observations.byStation[st.id].orEmpty().any { it.timeMs in ride.startMs..ride.endMs && it.wind != null }
        }
        return RidePrep(ride, rho, accel, course, model, measured, stations)
    }

    /** Start index of the 20-minute stretch with the lowest archived best_match wind along the track. */
    private fun calmestWindowStart(ride: Ride): Int {
        val s = ride.samples
        val member = corpus.forecasts.members.firstOrNull { it == UpstreamPipeline.BEST_MATCH } ?: corpus.forecasts.members.firstOrNull() ?: return 0
        val res = if (corpus.forecasts.has(member, Resolution.HOURLY)) Resolution.HOURLY else Resolution.MINUTELY_15
        val speeds = s.indices.filter { it % 60 == 0 }.map { i ->
            i to (corpus.forecasts.seriesAt(member, res, s[i].point)?.windAt(s[i].timeMs)?.speed ?: Double.MAX_VALUE)
        }
        val windowSamples = calmFitWindowS / 60
        if (speeds.size <= windowSamples) return 0
        var best = 0; var bestMean = Double.MAX_VALUE
        for (k in 0..speeds.size - windowSamples) {
            val m = speeds.subList(k, k + windowSamples).map { it.second }.average()
            if (m < bestMean) { bestMean = m; best = speeds[k].first }
        }
        return best
    }

    private fun runRide(config: ReplayConfig, prep: RidePrep, acc: ConfigAccumulator) {
        val ride = prep.ride
        val mass = ride.systemMassKg ?: Ride.DEFAULT_SYSTEM_MASS_KG
        val env = ReplayEnvironment.create(corpus.forecasts, corpus.observations, mass, startMs = ride.startMs)
        val pipeline = config.create(env) ?: return
        val outputs = ArrayList<ReplayOutput>(ride.samples.size)
        for (sample in ride.samples) {
            env.clock.set(sample.timeMs)
            val t0 = System.nanoTime()
            outputs += pipeline.tick(sample)
            acc.tickNanos += System.nanoTime() - t0
            acc.ticks++
        }
        acc.fetches += env.fetchRequests

        val rideResult = linkedMapOf<String, Any?>("ride" to ride.id)

        // Metric B
        if (prep.model != null) {
            val headwind = DoubleArray(outputs.size) { outputs[it].headwind ?: 0.0 }
            val dh = VirtualElevation.increments(ride.samples, headwind, prep.model, prep.rho, prep.accel)
            val ve = VirtualElevation.windowRmse(ride.samples, dh)
            val (fwd, fwdN) = VirtualElevation.forwardPredictionRmse(ride.samples, outputs, prep.measured!!, prep.course)
            ve?.let { acc.veSquares += it * it; acc.veN++ }
            fwd?.let { acc.fwdSquares += it * it * fwdN; acc.fwdN += fwdN }
            rideResult["ve_rmse_m"] = ve
            rideResult["headwind_forward_rmse_ms"] = fwd
            rideResult["fit_cda"] = prep.model.cdA
            rideResult["fit_crr"] = prep.model.crr
            // Synthetic rides: the floor this metric can reach (truth wind, truth CdA/Crr).
            ride.truth?.let { t ->
                var lastCourse = 0.0
                val hwTruth = DoubleArray(outputs.size) { i -> lastCourse = prep.course[i] ?: lastCourse; t.riderWind[i].headwind(lastCourse) }
                val dhTruth = VirtualElevation.increments(ride.samples, hwTruth, PowerModel(mass, t.cdA, t.crr), prep.rho, prep.accel)
                rideResult["ve_rmse_truth_floor_m"] = VirtualElevation.windowRmse(ride.samples, dhTruth)
            }
        }

        // Metric C
        val (jMean, jP95, jN) = HeadingMetrics.stoppedJitter(ride.samples, outputs)
        val (lag, _, lagN) = HeadingMetrics.turnLag(ride.samples, outputs, prep.course)
        jMean?.let { acc.jitterMeans += it to jN }
        jP95?.let { acc.jitterP95 += it }
        lag?.let { acc.lags += it to lagN }
        rideResult["stopped_jitter_deg"] = jMean
        rideResult["turn_lag_s"] = lag
        rideResult["turns"] = lagN

        // Metric A: leave-one-station-out runs over the ride's time window
        var pairsForRide = 0
        for (station in prep.stations) {
            val added = runStation(config, station, prep, mass)
            acc.pairs += added
            pairsForRide += added.size
        }
        rideResult["metric_a_pairs"] = pairsForRide
        rideResult["metric_a_stations"] = prep.stations.size
        acc.rides += rideResult
    }

    /** Leave [station] out, put the "rider" on it for the ride's time window, compare with what it reported. */
    private fun runStation(config: ReplayConfig, station: StationInfo, prep: RidePrep, mass: Double): List<WindPair> {
        val ride = prep.ride
        val history = corpus.observations.byStation[station.id].orEmpty()
        val flags = MetricA.shiftFlags(history.map { it.timeMs }, history.map { it.wind })
        val inWindow = history.indices.filter { history[it].timeMs in ride.startMs..ride.endMs && history[it].wind != null }
        if (inWindow.isEmpty()) return emptyList()
        val others = prep.stations.filter { it.id != station.id }
        val nearest = others.minOfOrNull { Geo.distanceKm(it.location, station.location) }

        val env = ReplayEnvironment.create(corpus.forecasts, corpus.observations, mass, heldOutStation = station.id, startMs = history[inWindow.first()].timeMs)
        val pipeline = config.create(env) ?: return emptyList()
        return inWindow.mapNotNull { i ->
            val o = history[i]
            env.clock.set(o.timeMs)
            val out = pipeline.tick(RideSample(timeMs = o.timeMs, lat = station.location.lat, lon = station.location.lon, gpsAccuracyM = 5.0, speedMps = 0.0))
            val est = out.model10m ?: return@mapNotNull null
            WindPair(station.id, o.timeMs, est, o.wind!!, flags[i], nearest)
        }
    }

    private fun summarize(config: ReplayConfig, acc: ConfigAccumulator): Map<String, Any?> {
        val jitterWindows = acc.jitterMeans.sumOf { it.second }
        val lagTurns = acc.lags.sumOf { it.second }
        return linkedMapOf(
            "available" to true,
            "implementation" to config.implementation,
            "metric_a" to MetricA.summarize(acc.pairs),
            "metric_b" to linkedMapOf(
                "rides_with_power" to acc.veN,
                "ve_rmse_m" to if (acc.veN == 0) null else sqrt(acc.veSquares / acc.veN),
                "headwind_forward_rmse_ms" to if (acc.fwdN == 0) null else sqrt(acc.fwdSquares / acc.fwdN),
                "forward_points" to acc.fwdN,
            ),
            "metric_c" to linkedMapOf(
                "stopped_jitter_deg" to if (jitterWindows == 0) null else acc.jitterMeans.sumOf { it.first * it.second } / jitterWindows,
                "stopped_jitter_p95_deg" to acc.jitterP95.maxOrNull(),
                "stopped_windows" to jitterWindows,
                "turn_lag_s" to if (lagTurns == 0) null else acc.lags.sumOf { it.first * it.second } / lagTurns,
                "turns" to lagTurns,
            ),
            "cost" to linkedMapOf(
                "fetch_requests" to acc.fetches,
                "ticks" to acc.ticks,
                "mean_tick_us" to if (acc.ticks == 0L) null else acc.tickNanos / 1000.0 / acc.ticks,
            ),
            "rides" to acc.rides,
        )
    }

    /** Relative changes against `upstream` and the TESTING 3.6 targets, evaluated on this corpus. */
    private fun compare(s: Map<String, Map<String, Any?>>): Map<String, Any?> {
        val base = s["upstream"] ?: return emptyMap()
        fun num(m: Map<String, Any?>?, vararg path: String): Double? {
            var cur: Any? = m
            for (p in path) cur = (cur as? Map<*, *>)?.get(p)
            return (cur as? Number)?.toDouble()
        }
        fun rel(a: Double?, b: Double?): Double? = if (a == null || b == null || b == 0.0) null else (a - b) / b
        val out = linkedMapOf<String, Any?>()
        for ((name, m) in s) {
            if (name == "upstream") continue
            val regimes = listOf("calm", "moderate", "strong", "shift").associateWith {
                rel(num(m, "metric_a", "regimes", it, "vector_rmse_ms"), num(base, "metric_a", "regimes", it, "vector_rmse_ms"))
            }
            out[name] = linkedMapOf(
                "metric_a_vector_rmse_rel" to rel(num(m, "metric_a", "overall", "vector_rmse_ms"), num(base, "metric_a", "overall", "vector_rmse_ms")),
                "metric_a_vector_rmse_rel_by_regime" to regimes,
                "metric_a_shift_direction_mae_rel" to rel(num(m, "metric_a", "regimes", "shift", "direction_mae_deg"), num(base, "metric_a", "regimes", "shift", "direction_mae_deg")),
                "metric_b_ve_rmse_rel" to rel(num(m, "metric_b", "ve_rmse_m"), num(base, "metric_b", "ve_rmse_m")),
                "metric_c_stopped_jitter_deg" to num(m, "metric_c", "stopped_jitter_deg"),
                "metric_c_turn_lag_delta_s" to num(m, "metric_c", "turn_lag_s")?.let { a -> num(base, "metric_c", "turn_lag_s")?.let { a - it } },
            )
        }
        // TESTING 3.6 targets (informational on a fixture corpus; the gate applies to the full corpus).
        val obs = out["+obs"] as? Map<*, *>
        val height = out["+height"] as? Map<*, *>
        val heading = out["+heading"] as? Map<*, *>
        val fwdHeight = num(s["+height"], "metric_b", "headwind_forward_rmse_ms")
        val fwdVirtual = num(s["+virtual"], "metric_b", "headwind_forward_rmse_ms")
        out["targets"] = linkedMapOf(
            "obs_vector_rmse_20pct_lower" to (obs?.get("metric_a_vector_rmse_rel") as? Double)?.let { it <= -0.20 },
            "obs_no_regime_5pct_worse" to (obs?.get("metric_a_vector_rmse_rel_by_regime") as? Map<*, *>)?.values?.filterIsInstance<Double>()?.let { v -> v.isNotEmpty() && v.all { it <= 0.05 } },
            "obs_shift_direction_30pct_lower" to (obs?.get("metric_a_shift_direction_mae_rel") as? Double)?.let { it <= -0.30 },
            "height_ve_rmse_25pct_lower" to (height?.get("metric_b_ve_rmse_rel") as? Double)?.let { it <= -0.25 },
            "virtual_forward_15pct_lower_than_height" to if (fwdHeight != null && fwdVirtual != null) fwdVirtual <= 0.85 * fwdHeight else null,
            "heading_stopped_jitter_under_5deg" to (heading?.get("metric_c_stopped_jitter_deg") as? Double)?.let { it < 5.0 },
            "heading_turn_lag_within_1s" to (heading?.get("metric_c_turn_lag_delta_s") as? Double)?.let { it <= 1.0 },
        )
        return out
    }
}

/** Track points of a ride, one per [everyS] seconds (for downloaders and station selection). */
fun Ride.trackPoints(everyS: Int = 60): List<GeoPoint> = samples.filterIndexed { i, _ -> i % everyS == 0 }.map { it.point } + samples.last().point
