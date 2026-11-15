/*
 * Copyright 2024-2026 karoo-headwind contributors
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
 *
 * Modified by TrueWind: karoo-headwind's upcoming-route sampling, extracted unchanged from
 * KarooHeadwindExtension into a function shared by the legacy fetch loop and the WindEngine wiring;
 * route bearing helper added.
 */

package de.timklge.karooheadwind.pipeline

import com.mapbox.geojson.Point
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
import de.timklge.karooheadwind.UpcomingRoute
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/**
 * The rider position followed by points along the upcoming route, one per hour of riding at
 * [distancePerHourM] (first at the next full hour), plus the route end; at most 10 points.
 */
fun upcomingRoutePoints(
    gps: GpsCoordinates,
    upcomingRoute: UpcomingRoute,
    distancePerHourM: Double,
    now: LocalDateTime = LocalDateTime.now(),
): List<GpsCoordinates> {
    val positionOnRoute = upcomingRoute.distanceAlongRoute
    val msSinceFullHour = ChronoUnit.MILLIS.between(now.truncatedTo(ChronoUnit.HOURS), now)
    val msToNextFullHour = (1_000 * 60 * 60) - msSinceFullHour
    val calculatedDistanceToNextFullHour = ((msToNextFullHour / (1_000.0 * 60 * 60)) * distancePerHourM).coerceIn(0.0, distancePerHourM)

    return buildList {
        add(GpsCoordinates(gps.lat, gps.lon, gps.bearing, distanceAlongRoute = positionOnRoute))

        var currentPosition = positionOnRoute + calculatedDistanceToNextFullHour
        var lastRequestedPosition = positionOnRoute

        while (currentPosition < upcomingRoute.routeLength && size < 10) {
            val point = TurfMeasurement.along(upcomingRoute.routePolyline, currentPosition, TurfConstants.UNIT_METERS)
            add(GpsCoordinates(point.latitude(), point.longitude(), distanceAlongRoute = currentPosition))
            lastRequestedPosition = currentPosition
            currentPosition += distancePerHourM
        }

        if (upcomingRoute.routeLength > lastRequestedPosition + 1_000) {
            val point = TurfMeasurement.along(upcomingRoute.routePolyline, upcomingRoute.routeLength, TurfConstants.UNIT_METERS)
            add(GpsCoordinates(point.latitude(), point.longitude(), distanceAlongRoute = upcomingRoute.routeLength))
        }
    }
}

/** Bearing of the route at the rider's distance along it (degrees from north), or null. */
fun routeBearingAt(upcomingRoute: UpcomingRoute, lookAheadM: Double = 30.0): Double? {
    val d = upcomingRoute.distanceAlongRoute
    if (upcomingRoute.routeLength < 2 * lookAheadM) return null
    val from = (d - lookAheadM / 2).coerceIn(0.0, upcomingRoute.routeLength - lookAheadM)
    return try {
        val a: Point = TurfMeasurement.along(upcomingRoute.routePolyline, from, TurfConstants.UNIT_METERS)
        val b: Point = TurfMeasurement.along(upcomingRoute.routePolyline, from + lookAheadM, TurfConstants.UNIT_METERS)
        (TurfMeasurement.bearing(a, b) + 360.0) % 360.0
    } catch (e: Exception) {
        null
    }
}
