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
import io.github.kf6999.truewind.core.model.WindVector
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * One recorded sample of a ride, normally 1 Hz. Field names follow the Karoo streams the app
 * feeds into `SensorHub`, so a ride can be replayed both here and on the device.
 */
data class RideSample(
    val timeMs: Long,
    val lat: Double,
    val lon: Double,
    /** Barometric (or GPS) altitude, m. */
    val altitudeM: Double? = null,
    /** Ground speed, m/s. */
    val speedMps: Double? = null,
    val powerW: Double? = null,
    val cadenceRpm: Double? = null,
    /** GPS course (Karoo `LOC_BEARING`), degrees; null when the receiver reports none. */
    val gpsBearingDeg: Double? = null,
    val gpsAccuracyM: Double? = null,
    /** Karoo `HEADING` stream, degrees; null when absent. */
    val headingDeg: Double? = null,
    val temperatureC: Double? = null,
    /** Grade, percent (Karoo `ELEVATION_GRADE`), when recorded. */
    val gradePct: Double? = null,
) {
    val point: GeoPoint get() = GeoPoint(lat, lon)
}

/** Known truth for synthetic rides; absent for real rides. */
data class RideTruth(
    /** Rider-height wind used to synthesise the power trace, per sample (same index as [Ride.samples]). */
    val riderWind: List<WindVector>,
    /** Physical parameters the power trace was generated with. */
    val cdA: Double,
    val crr: Double,
)

data class Ride(
    val id: String,
    val samples: List<RideSample>,
    /** Rider + bike mass, kg; null = use [DEFAULT_SYSTEM_MASS_KG]. */
    val systemMassKg: Double? = null,
    val truth: RideTruth? = null,
    /** Free-form origin note (file name, "synthetic", ...). */
    val source: String = "",
) {
    val startMs: Long get() = samples.first().timeMs
    val endMs: Long get() = samples.last().timeMs
    val hasPower: Boolean get() = samples.count { (it.powerW ?: 0.0) > 0.0 } > samples.size / 4

    companion object {
        const val DEFAULT_SYSTEM_MASS_KG = 84.0
    }
}

/** A weather station known to the corpus. */
data class StationInfo(
    val id: String,
    val source: String,
    val location: GeoPoint,
    val elevationM: Double? = null,
)

/**
 * What a pipeline displays at one tick. All wind vectors use the core convention (u east, v north,
 * direction blowing TO).
 */
data class ReplayOutput(
    val timeMs: Long,
    /** Fused 10 m wind at the rider (or at the held-out station in a Metric A run). */
    val model10m: WindVector?,
    /** Rider-height wind (the headline value). */
    val rider: WindVector?,
    /** Heading used for the relative quantities, degrees. */
    val headingDeg: Double?,
    val confidence: Int? = null,
    /** Debug: members that contributed, for the report. */
    val members: List<String> = emptyList(),
) {
    /** Headwind at rider height, m/s, + into the rider. */
    val headwind: Double? get() = if (rider != null && headingDeg != null) rider.headwind(headingDeg) else null

    /**
     * Direction the wind comes from relative to the heading, 0 = dead ahead, clockwise, [0, 360) —
     * the angle the headwind arrow shows.
     */
    val relativeDirectionDeg: Double?
        get() = if (rider != null && headingDeg != null && rider.speed > 0.0) {
            io.github.kf6999.truewind.core.math.normalizeDegrees(rider.directionFromDeg - headingDeg)
        } else null
}

object Geo {
    private const val EARTH_RADIUS_KM = 6371.0

    fun distanceKm(a: GeoPoint, b: GeoPoint): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_KM * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    /** Initial great-circle bearing from [a] to [b], degrees [0, 360). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val la1 = Math.toRadians(a.lat)
        val la2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(la2)
        val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
        return io.github.kf6999.truewind.core.math.normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /** Moves [p] by [eastM] / [northM] metres (flat-earth, fine for a few tens of km). */
    fun offset(p: GeoPoint, eastM: Double, northM: Double): GeoPoint {
        val dLat = northM / 111_320.0
        val dLon = eastM / (111_320.0 * cos(Math.toRadians(p.lat)))
        return GeoPoint(p.lat + dLat, p.lon + dLon)
    }

    /** East/north metres of [p] relative to [origin] (flat-earth). */
    fun toLocalM(origin: GeoPoint, p: GeoPoint): Pair<Double, Double> =
        (p.lon - origin.lon) * 111_320.0 * cos(Math.toRadians(origin.lat)) to (p.lat - origin.lat) * 111_320.0
}
