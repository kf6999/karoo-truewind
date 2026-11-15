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

package io.github.kf6999.truewind.core.math

import io.github.kf6999.truewind.core.model.GeoPoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius, km. */
const val EARTH_RADIUS_KM = 6371.0088

/** Kilometres per degree of latitude (and of longitude at the equator). */
const val KM_PER_DEGREE = 111.32

/** Great-circle distance between two points, km (haversine). */
fun haversineKm(a: GeoPoint, b: GeoPoint): Double {
    val lat1 = Math.toRadians(a.lat)
    val lat2 = Math.toRadians(b.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
    return 2 * EARTH_RADIUS_KM * asin(min(1.0, sqrt(h)))
}
