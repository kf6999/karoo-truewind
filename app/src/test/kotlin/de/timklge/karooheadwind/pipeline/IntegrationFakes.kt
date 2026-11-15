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

package de.timklge.karooheadwind.pipeline

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.StreamState
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/*
 * T2 integration fakes (TESTING.md 2): a scripted core HttpClient and a ride feeder that pushes a
 * corpus ride (corpus/fixtures/<corpus>/rides/<id>.csv) into FakeKarooStreamSource. The clock is
 * the test scheduler (`Clock { base + testScheduler.currentTime }`) or core's ManualClock.
 */

/**
 * Core [HttpClient] fake: routes by URL substring (first match wins), records every request,
 * answers 404 for unrouted URLs or a transport error when [offline]. Bodies can be loaded from
 * recorded files under corpus/ (e.g. [fromFile]).
 */
class FakeHttpClient : HttpClient {
    private val routes = CopyOnWriteArrayList<Pair<String, (HttpRequest) -> HttpResponse>>()
    val requests = CopyOnWriteArrayList<HttpRequest>()
    var offline = false

    fun route(urlContains: String, response: (HttpRequest) -> HttpResponse) = apply { routes.add(urlContains to response) }
    fun json(urlContains: String, body: String, status: Int = 200) =
        route(urlContains) { HttpResponse(status, mapOf("Content-Type" to "application/json"), body.toByteArray()) }

    fun count(urlContains: String) = requests.count { urlContains in it.url }

    override suspend fun request(request: HttpRequest): HttpResponse {
        requests.add(request)
        if (offline) return HttpResponse(0, error = "offline (FakeHttpClient)")
        return routes.firstOrNull { it.first in request.url }?.second?.invoke(request) ?: HttpResponse(404, body = "not routed".toByteArray())
    }

    companion object {
        /** A file relative to the repository root (unit tests run with the module dir as cwd). */
        fun fromFile(path: String): String = (File(path).takeIf { it.exists() } ?: File("..", path)).readText()
    }
}

/** One second of a corpus ride CSV (the replay harness format). */
data class RideRow(
    val timeMs: Long, val lat: Double, val lon: Double, val altitudeM: Double?, val speedMps: Double?, val powerW: Double?,
    val cadenceRpm: Double?, val bearingDeg: Double?, val accuracyM: Double?, val headingDeg: Double?, val temperatureC: Double?,
)

/** Feeds a corpus ride into a [FakeKarooStreamSource] as the Karoo's streams would deliver it. */
class RideFeeder(val rows: List<RideRow>) {
    val startMs get() = rows.first().timeMs

    /** Pushes row [i] into LOCATION, SPEED, POWER, CADENCE, HEADING, TEMPERATURE and ELEVATION. */
    suspend fun emit(source: FakeKarooStreamSource, i: Int) {
        val r = rows[i]
        source.data(DataType.Type.LOCATION).emit(
            StreamState.Streaming(
                DataPoint(DataType.Type.LOCATION, buildMap {
                    put(DataType.Field.LOC_LATITUDE, r.lat)
                    put(DataType.Field.LOC_LONGITUDE, r.lon)
                    r.bearingDeg?.let { put(DataType.Field.LOC_BEARING, it) }
                    r.accuracyM?.let { put(DataType.Field.LOC_ACCURACY, it) }
                }),
            ),
        )
        fun single(type: String, v: Double?) = v?.let { StreamState.Streaming(DataPoint(type, mapOf(DataType.Field.SINGLE to it))) }
        single(DataType.Type.SPEED, r.speedMps)?.let { source.data(DataType.Type.SPEED).emit(it) }
        single(DataType.Type.POWER, r.powerW)?.let { source.data(DataType.Type.POWER).emit(it) }
        single(DataType.Type.CADENCE, r.cadenceRpm)?.let { source.data(DataType.Type.CADENCE).emit(it) }
        single(DataType.Type.HEADING, r.headingDeg)?.let { source.data(DataType.Type.HEADING).emit(it) }
        single(DataType.Type.TEMPERATURE, r.temperatureC)?.let { source.data(DataType.Type.TEMPERATURE).emit(it) }
    }

    /** The same ride shifted so it starts at [startMs]. */
    fun startingAt(startMs: Long): RideFeeder = (startMs - this.startMs).let { d -> RideFeeder(rows.map { it.copy(timeMs = it.timeMs + d) }) }

    companion object {
        fun fromCsv(path: String, maxRows: Int = Int.MAX_VALUE): RideFeeder {
            val lines = FakeHttpClient.fromFile(path).lineSequence().filter { it.isNotBlank() && !it.startsWith("#") }.toList()
            val header = lines.first().split(',')
            fun idx(name: String) = header.indexOf(name)
            val rows = lines.drop(1).take(maxRows).map { line ->
                val c = line.split(',')
                fun d(name: String) = idx(name).takeIf { it >= 0 }?.let { c.getOrNull(it) }?.toDoubleOrNull()
                RideRow(
                    c[idx("time_ms")].toLong(), d("lat")!!, d("lon")!!, d("alt_m"), d("speed_mps"), d("power_w"),
                    d("cadence_rpm"), d("bearing_deg"), d("accuracy_m"), d("heading_deg"), d("temp_c"),
                )
            }
            return RideFeeder(rows)
        }

        const val SFBAY_RIDE = "corpus/fixtures/recorded-sfbay-front/rides/sfbay-loop-front.csv"
    }
}
