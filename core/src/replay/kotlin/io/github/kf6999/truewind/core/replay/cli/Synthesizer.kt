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

import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.replay.Geo
import io.github.kf6999.truewind.core.replay.ReportJson
import io.github.kf6999.truewind.core.replay.VirtualElevation
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Deterministic synthetic corpus generator (fixture corpus for CI). Everything derives from one
 * known "truth" wind field, so the metrics have a right answer:
 *
 * - truth 10 m wind: SW flow with a 45-min speed oscillation, then a cold front (SW -> NW, 4.5 ->
 *   8.5 m/s) sweeping east at 30 km/h; mild spatial modulation;
 * - forecast members = truth seen with a timing lag, speed scale, rotation and temporal smoothing
 *   per member (best_match duplicates the HRRR-like member, as Open-Meteo does in CONUS);
 * - stations = truth x shelter factor + noise, 5-min reports, direction rounded to 10 deg;
 * - rides = paths with stops and a slow switchback; power from the SPEC 4.6 power balance with the
 *   rider-height truth wind (log-law ratio 0.54); GPS and compass noise.
 *
 * Results on this corpus show the harness works and how layers respond to *these* error types.
 * They are not evidence of real-world accuracy.
 */
class Synthesizer(private val seed: Long = 42L) {
    private val rnd = java.util.Random(seed)
    private fun gauss() = rnd.nextGaussian()

    val origin = GeoPoint(39.10, -100.30)
    /** 2026-06-14 15:00 UTC */
    val t0Ms = 1_781_449_200_000L

    fun truth10m(p: GeoPoint, tMs: Long): WindVector {
        val (xM, yM) = Geo.toLocalM(origin, p)
        val tMin = (tMs - t0Ms) / 60_000.0
        val frontMin = 80.0 + (xM / 1000.0) / 0.5
        val f = 1.0 / (1.0 + exp(-(tMin - frontMin) / 6.0))
        val pre = WindVector.fromSpeedDirection(4.5 + 0.8 * sin(2 * PI * tMin / 45.0), 200.0 + 8 * sin(2 * PI * tMin / 70.0))
        val post = WindVector.fromSpeedDirection(8.5, 300.0)
        val w = pre * (1 - f) + post * f
        return w * (1.0 + 0.08 * sin(yM / 6000.0))
    }

    data class Member(val id: String, val lagMin: Double, val scale: Double, val rotDeg: Double, val smoothSteps: Int)

    val members = listOf(
        Member("best_match", 50.0, 0.92, 5.0, 0),
        Member("ncep_hrrr_conus", 50.0, 0.92, 5.0, 0),
        Member("ncep_nbm_conus", 20.0, 1.12, -10.0, 2),
        Member("ecmwf_ifs", -25.0, 0.85, 15.0, 4),
        Member("gfs_seamless", 70.0, 1.15, 0.0, 3),
    )

    fun memberWind(m: Member, p: GeoPoint, tMs: Long): WindVector {
        var acc = WindVector.ZERO
        val n = m.smoothSteps
        for (k in -n..n) acc += truth10m(p, tMs - (m.lagMin * 60_000).toLong() + k * 10 * 60_000L)
        val w = acc / (2 * n + 1).toDouble() * m.scale
        return WindVector.fromSpeedDirection(w.speed, normalizeDegrees(w.directionFromDeg + m.rotDeg))
    }

    data class Station(val id: String, val eastKm: Double, val northKm: Double, val shelter: Double)

    val stations = listOf(
        Station("SYN1", -12.0, 5.0, 0.95), Station("SYN2", 8.0, 10.0, 1.0), Station("SYN3", 15.0, -6.0, 0.9),
        Station("SYN4", -5.0, -14.0, 1.05), Station("SYN5", 3.0, 2.0, 0.85), Station("SYN6", 22.0, 15.0, 1.0),
        Station("SYN7", -20.0, -10.0, 0.95),
    )

    fun terrain(p: GeoPoint): Double {
        val (x, y) = Geo.toLocalM(origin, p)
        return 300 + 25 * sin(x / 1700.0) + 15 * cos(y / 2300.0)
    }

    /** Writes the corpus to [dir] (rides/, forecasts/, observations/). */
    fun write(dir: File, startOffsetMin: Long = -90, durationMin: Long = 330) {
        File(dir, "rides").mkdirs(); File(dir, "forecasts").mkdirs(); File(dir, "observations").mkdirs()
        val from = t0Ms + startOffsetMin * 60_000; val to = from + durationMin * 60_000

        // Forecast grid +-0.2 deg every 0.05 deg.
        val grid = (-4..4).flatMap { i -> (-4..4).map { j -> GeoPoint(origin.lat + i * 0.05, origin.lon + j * 0.05) } }
        for (m in members) {
            val objs = grid.map { p ->
                fun block(step: Long): Map<String, Any?> {
                    val times = (from / step * step..to step step).toList()
                    val w = times.map { memberWind(m, p, it) }
                    return linkedMapOf(
                        "time" to times.map { it / 1000 },
                        "wind_speed_10m" to w.map { r2(it.speed) },
                        "wind_direction_10m" to w.map { it.directionFromDeg.roundToInt() },
                        "wind_gusts_10m" to w.map { r2(it.speed * 1.4) },
                    )
                }
                linkedMapOf(
                    "latitude" to r4(p.lat), "longitude" to r4(p.lon), "elevation" to terrain(p).roundToInt(), "utc_offset_seconds" to 0,
                    "hourly_units" to mapOf("wind_speed_10m" to "m/s"),
                    "hourly" to block(3_600_000L),
                    "minutely_15" to block(900_000L),
                )
            }
            File(dir, "forecasts/${m.id}.json").writeText(ReportJson.write(objs, indent = ""))
        }

        // Stations: 5-min reports.
        val obsLines = mutableListOf("station,source,lat,lon,elev_m,time_ms,speed_mps,dir_deg,gust_mps,sensor_height_m")
        for (s in stations) {
            val p = Geo.offset(origin, s.eastKm * 1000, s.northKm * 1000)
            var t = from
            while (t <= to) {
                val w = truth10m(p, t) * s.shelter + WindVector(0.6 * gauss(), 0.6 * gauss())
                val kt = (w.speed / 0.514444).roundToInt()
                val speed = kt * 0.514444
                val dirStr = if (kt == 0) "0" else (((w.directionFromDeg / 10).roundToInt() * 10) % 360).let { if (it == 0) 360 else it }.toString()
                obsLines += listOf(s.id, "synthetic", f(p.lat, 5), f(p.lon, 5), f(terrain(p), 1), t.toString(), f(speed, 2), dirStr, "", "10").joinToString(",")
                t += 5 * 60_000
            }
        }
        File(dir, "observations/stations.csv").writeText(obsLines.joinToString("\n") + "\n")

        writeRide(dir, "syn-loop-front", t0Ms, loopPath(), stopsEvery = 2, minutes = 150, truth = ::truth10m)
        writeRide(dir, "syn-outback", t0Ms + 20 * 60_000, outAndBackPath(), stopsEvery = 1, minutes = 75, truth = ::truth10m)
        File(dir, "corpus.json").writeText(ReportJson.write(linkedMapOf(
            "name" to dir.name,
            "description" to "Synthetic fixture corpus (seed $seed): known truth wind field with a cold front, 5 biased forecast members, 7 noisy stations, 2 rides with power. Generated by `./gradlew :core:replay --args=\"synthesize ${dir.path}\"`.",
        )))
    }

    /** Loop of a 6 x 4 km rectangle with a short switchback leg, metres east/north of origin. */
    private fun loopPath(): List<Waypoint> = listOf(
        Waypoint(0.0, 0.0, stop = true), Waypoint(3000.0, 0.0), Waypoint(3000.0, 2000.0, stop = true), Waypoint(3100.0, 2150.0, slow = true),
        Waypoint(2900.0, 2300.0, slow = true), Waypoint(3100.0, 2450.0, slow = true), Waypoint(3000.0, 4000.0), Waypoint(-3000.0, 4000.0, stop = true),
        Waypoint(-3000.0, 0.0),
    )

    private fun outAndBackPath(): List<Waypoint> = listOf(
        Waypoint(-6000.0, -3000.0, stop = true), Waypoint(6000.0, -3000.0, stop = true), Waypoint(6000.0, -1000.0), Waypoint(-6000.0, -1000.0),
    )

    data class Waypoint(val eastM: Double, val northM: Double, val stop: Boolean = false, val slow: Boolean = false)

    /**
     * Rides the closed polyline [path] at 1 Hz for [minutes]. Stops 45 s at `stop` waypoints on every
     * [stopsEvery]-th lap, crawls at 1.5 m/s into `slow` waypoints.
     */
    fun writeRide(dir: File, id: String, startMs: Long, path: List<Waypoint>, stopsEvery: Int, minutes: Int, truth: (GeoPoint, Long) -> WindVector) {
        val massKg = 84.0; val cda = 0.32; val crr = 0.005; val eta = 0.975; val ratio = 0.54
        val lines = mutableListOf("time_ms,lat,lon,alt_m,speed_mps,power_w,cadence_rpm,bearing_deg,accuracy_m,heading_deg,temp_c,truth_u,truth_v")
        var seg = 0; var along = 0.0; var lap = 0
        var v = 0.0; var stopLeft = 0
        var gpsNoiseE = 0.0; var gpsNoiseN = 0.0
        var lastReported: GeoPoint? = null
        var lastBearing: Double? = null
        var travelHeading = 0.0
        val compassBias = 3.0
        var altFiltered: Double? = null
        for (sec in 0 until minutes * 60) {
            val t = startMs + sec * 1000L
            val a = path[seg]; val b = path[(seg + 1) % path.size]
            val segLen = sqrt((b.eastM - a.eastM).let { it * it } + (b.northM - a.northM).let { it * it })
            travelHeading = normalizeDegrees(Math.toDegrees(kotlin.math.atan2(b.eastM - a.eastM, b.northM - a.northM)))
            val remaining = segLen - along
            val pos = Geo.offset(origin, a.eastM + (b.eastM - a.eastM) * along / segLen, a.northM + (b.northM - a.northM) * along / segLen)
            val ahead = Geo.offset(origin, a.eastM + (b.eastM - a.eastM) * min(segLen, along + 20) / segLen, a.northM + (b.northM - a.northM) * min(segLen, along + 20) / segLen)
            val grade = ((terrain(ahead) - terrain(pos)) / 20.0).coerceIn(-0.1, 0.1)

            val stopHere = b.stop && lap % stopsEvery == 0
            val target = when {
                stopLeft > 0 -> 0.0
                b.slow || a.slow -> 1.5
                stopHere && remaining < 40 -> max(0.0, remaining / 8.0)
                remaining < 25 -> 4.5
                else -> (8.5 - 40 * grade).coerceIn(4.5, 11.0)
            }
            val dv = (target - v).coerceIn(-1.2, 0.5)
            val vNew = max(0.0, v + dv)
            val accel = vNew - v
            v = vNew
            if (stopLeft > 0) stopLeft--

            val rho = VirtualElevation.airDensity(terrain(pos), 12.0)
            val w = truth(pos, t) * ratio
            val hw = w.headwind(travelHeading)
            val va = v + hw
            var power = if (v > 0.2) (massKg * VirtualElevation.G * v * (grade + crr) + massKg * accel * v + 0.5 * rho * cda * va * abs(va) * v) / eta else 0.0
            power = if (power < 0) 0.0 else power * (1 + 0.04 * gauss())

            gpsNoiseE = 0.9 * gpsNoiseE + 0.8 * gauss(); gpsNoiseN = 0.9 * gpsNoiseN + 0.8 * gauss()
            val reported = Geo.offset(pos, gpsNoiseE, gpsNoiseN)
            val bearing = lastReported?.let { lr ->
                if (Geo.distanceKm(lr, reported) * 1000 > 0.05) Geo.bearingDeg(lr, reported) else lastBearing
            }
            lastReported = reported; lastBearing = bearing
            val heading = normalizeDegrees(travelHeading + compassBias + 6 * gauss())
            val altRaw = terrain(pos) + 0.3 * gauss()
            altFiltered = altFiltered?.let { it + 0.5 * (altRaw - it) } ?: altRaw
            val tw = truth(pos, t)
            lines += listOf(
                t.toString(), f(reported.lat, 7), f(reported.lon, 7), f(altFiltered!!, 2), f(v, 3), f(power, 1),
                if (power > 0) "85" else "0", bearing?.let { f(it, 1) } ?: "", "4", f(heading, 1), "12", f(tw.u * ratio, 3), f(tw.v * ratio, 3),
            ).joinToString(",")

            along += v
            if (along >= segLen) {
                along -= segLen
                if (stopHere && stopLeft == 0) { stopLeft = 45; along = 0.0; v = 0.0 }
                seg = (seg + 1) % path.size
                if (seg == 0) lap++
            }
        }
        File(dir, "rides/$id.csv").writeText(lines.joinToString("\n") + "\n")
        File(dir, "rides/$id.json").writeText(ReportJson.write(linkedMapOf(
            "system_mass_kg" to massKg, "truth_cda" to cda, "truth_crr" to crr, "truth_height_ratio" to ratio, "synthetic" to true,
        )))
    }

    /**
     * Synthesises a ride inside an existing (downloaded) corpus whose truth wind is the
     * inverse-distance-weighted, time-interpolated station observations of that corpus.
     */
    fun writeRideFromObservations(dir: File, id: String, center: GeoPoint, startMs: Long, minutes: Int, observations: List<Observation>) {
        val byStation = observations.filter { it.wind != null }.groupBy { it.stationId }.mapValues { (_, l) -> l.sortedBy { it.timeMs } }
        fun truth(p: GeoPoint, t: Long): WindVector {
            var su = 0.0; var sv = 0.0; var sw = 0.0
            for ((_, l) in byStation) {
                val idx = l.binarySearchBy(t) { it.timeMs }.let { if (it >= 0) it else -it - 2 }
                val o0 = l.getOrNull(idx) ?: continue
                val o1 = l.getOrNull(idx + 1)
                if (t - o0.timeMs > 3_600_000L) continue
                val w = if (o1 != null && o1.timeMs > o0.timeMs) o0.wind!! + (o1.wind!! - o0.wind!!) * ((t - o0.timeMs).toDouble() / (o1.timeMs - o0.timeMs)).coerceIn(0.0, 1.0) else o0.wind!!
                val d = max(1.0, Geo.distanceKm(p, o0.location))
                val wt = 1.0 / (d * d)
                su += wt * w.u; sv += wt * w.v; sw += wt
            }
            return if (sw == 0.0) WindVector.ZERO else WindVector(su / sw, sv / sw)
        }
        val saved = origin
        // Paths are relative to [origin]; re-centre by building waypoints around [center].
        val (dx, dy) = Geo.toLocalM(saved, center)
        val path = loopPath().map { it.copy(eastM = it.eastM * 0.8 + dx, northM = it.northM * 0.8 + dy) }
        writeRide(dir, id, startMs, path, stopsEvery = 2, minutes = minutes, truth = ::truth)
    }

    private fun r2(d: Double) = (d * 100).roundToInt() / 100.0
    private fun r4(d: Double) = (d * 10000).roundToInt() / 10000.0
    private fun f(d: Double, n: Int) = String.format(Locale.ROOT, "%.${n}f", d)
}
