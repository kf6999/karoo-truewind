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

package io.github.kf6999.truewind.core.route

import io.github.kf6999.truewind.core.math.KM_PER_DEGREE
import io.github.kf6999.truewind.core.math.normalizeDegrees
import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Small geodesy helpers shared by route weather, storm and wildfire distance. */
object GeoMath {
    /** Initial great-circle bearing from [a] to [b], degrees clockwise from north, [0, 360). */
    fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return normalizeDegrees(Math.toDegrees(atan2(y, x)))
    }

    /** Point at fraction [f] (0..1) between [a] and [b], linear in lat/lon (fine for short segments). */
    fun interpolate(a: GeoPoint, b: GeoPoint, f: Double): GeoPoint {
        var dLon = b.lon - a.lon
        if (dLon > 180) dLon -= 360 else if (dLon < -180) dLon += 360
        var lon = a.lon + dLon * f
        if (lon > 180) lon -= 360 else if (lon < -180) lon += 360
        return GeoPoint(a.lat + (b.lat - a.lat) * f, lon)
    }

    /**
     * Local equirectangular projection around [origin], km east (x) / north (y). Accurate to well
     * under 1% within ~100 km, which is all the proximity checks here need.
     */
    class LocalProjection(val origin: GeoPoint) {
        private val kx = KM_PER_DEGREE * cos(Math.toRadians(origin.lat))

        fun x(p: GeoPoint): Double {
            var d = p.lon - origin.lon
            if (d > 180) d -= 360 else if (d < -180) d += 360
            return d * kx
        }

        fun y(p: GeoPoint): Double = (p.lat - origin.lat) * KM_PER_DEGREE

        /** Distance, km, from the origin to the segment [a]-[b]. */
        fun distanceToSegmentKm(a: GeoPoint, b: GeoPoint): Double =
            pointSegmentDistance(0.0, 0.0, x(a), y(a), x(b), y(b))

        /** Closest point of segment [a]-[b] to the origin. */
        fun closestOnSegment(a: GeoPoint, b: GeoPoint): GeoPoint {
            val ax = x(a); val ay = y(a); val bx = x(b); val by = y(b)
            val t = segmentT(0.0, 0.0, ax, ay, bx, by)
            return interpolate(a, b, t)
        }
    }

    internal fun segmentT(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val dx = bx - ax
        val dy = by - ay
        val l2 = dx * dx + dy * dy
        return if (l2 == 0.0) 0.0 else (((px - ax) * dx + (py - ay) * dy) / l2).coerceIn(0.0, 1.0)
    }

    internal fun pointSegmentDistance(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val t = segmentT(px, py, ax, ay, bx, by)
        return hypot(px - (ax + t * (bx - ax)), py - (ay + t * (by - ay)))
    }
}
