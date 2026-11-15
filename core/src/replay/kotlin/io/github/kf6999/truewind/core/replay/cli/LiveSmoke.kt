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

import io.github.kf6999.truewind.core.alerts.nws.NwsAlerts
import io.github.kf6999.truewind.core.api.ForecastRequest
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.airquality.AirQualityProvider
import io.github.kf6999.truewind.core.providers.airquality.AirQualityResult
import io.github.kf6999.truewind.core.providers.obs.AwcMetarProvider
import io.github.kf6999.truewind.core.providers.obs.NwsObservationProvider
import io.github.kf6999.truewind.core.providers.obs.SynopticProvider
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoForecastProvider
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowParser
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowRequests
import io.github.kf6999.truewind.core.replay.ReportJson
import io.github.kf6999.truewind.core.replay.runSync
import io.github.kf6999.truewind.core.time.Clock
import java.net.URI
import java.net.http.HttpClient as JdkHttpClient
import java.net.http.HttpRequest as JdkHttpRequest
import java.net.http.HttpResponse as JdkHttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * Blocking JDK implementation of the core [HttpClient] for laptop-side live checks. Decodes gzip
 * like the Karoo's MakeHttpRequest does. [bytesOnWire] counts compressed bytes.
 */
class JdkHttp(private val userAgent: String = "TrueWind-replay/1.0") : HttpClient {
    private val client = JdkHttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(JdkHttpClient.Redirect.NORMAL).build()
    var requests = 0; private set
    var bytesOnWire = 0L; private set

    override suspend fun request(request: HttpRequest): HttpResponse {
        requests++
        return try {
            val b = JdkHttpRequest.newBuilder(URI(request.url)).timeout(Duration.ofSeconds(60))
                .method(request.method, request.body?.let { JdkHttpRequest.BodyPublishers.ofByteArray(it) } ?: JdkHttpRequest.BodyPublishers.noBody())
            val headers = request.headers.toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = userAgent
            headers.forEach { (k, v) -> b.header(k, v) }
            val r = client.send(b.build(), JdkHttpResponse.BodyHandlers.ofByteArray())
            bytesOnWire += r.body().size
            val gz = r.headers().firstValue("Content-Encoding").orElse("").contains("gzip")
            val body = if (gz) GZIPInputStream(r.body().inputStream()).readBytes() else r.body()
            HttpResponse(r.statusCode(), r.headers().map().mapValues { it.value.joinToString(",") }, body)
        } catch (e: Exception) {
            HttpResponse(0, error = e.toString())
        }
    }
}

/**
 * `live-smoke`: one live request to each provider through the production core classes (SPEC 9.4,
 * TESTING 7 nightly smoke). Keys come from the environment (`SYNOPTIC_TOKEN`, `TOMORROW_API_KEY`);
 * they are never printed. Returns the number of failed keyless providers.
 */
object LiveSmoke {
    fun run(point: GeoPoint): Int {
        val results = linkedMapOf<String, Any?>()
        var keylessFailures = 0
        fun probe(name: String, keyless: Boolean, block: (JdkHttp) -> Map<String, Any?>) {
            val http = JdkHttp()
            val t0 = System.nanoTime()
            val r = try { block(http) } catch (e: Exception) { mapOf("ok" to false, "error" to e.toString()) }
            val out = linkedMapOf<String, Any?>()
            out.putAll(r)
            out["ms"] = (System.nanoTime() - t0) / 1_000_000
            out["requests"] = http.requests
            out["bytes_on_wire"] = http.bytesOnWire
            results[name] = out
            if (keyless && r["ok"] != true) keylessFailures++
            System.err.println("live-smoke: $name ${if (r["ok"] == true) "OK" else "FAIL"} $out")
        }
        probe("open-meteo-ensemble", true) { http ->
            val d = runSync { OpenMeteoForecastProvider(http, Clock.SYSTEM).fetchDetailed(ForecastRequest(listOf(point), System.currentTimeMillis())) }
            mapOf("ok" to d.modelsUsed.isNotEmpty(), "models_used" to d.modelsUsed, "dropped" to d.droppedModels, "bytes" to d.bytesReceived)
        }
        probe("awc-metar", true) { http ->
            val r = runSync { AwcMetarProvider(http).fetchMetars(point, 40.0) }
            mapOf("ok" to (r.error == null && r.items.isNotEmpty()), "items" to r.items.size, "bytes" to r.bytesReceived, "error" to r.error)
        }
        probe("nws-observations", true) { http ->
            val r = runSync { NwsObservationProvider(http).fetchDetailed(point, 40.0) }
            mapOf("ok" to (r.error == null), "items" to r.items.size, "bytes" to r.bytesReceived, "error" to r.error)
        }
        probe("nws-alerts", true) { http ->
            val r = runSync { NwsAlerts.fetch(http, point) }
            mapOf("ok" to (r.error == null), "items" to r.items.size, "bytes" to r.bytesReceived, "error" to r.error)
        }
        probe("open-meteo-air-quality", true) { http ->
            when (val r = runSync { AirQualityProvider(http, Clock.SYSTEM).fetch(point, force = true) }) {
                is AirQualityResult.Success -> mapOf("ok" to true, "bytes" to r.bytesReceived)
                is AirQualityResult.Failure -> mapOf("ok" to false, "error" to r.reason, "status" to r.httpStatus)
            }
        }
        val synoptic = System.getenv("SYNOPTIC_TOKEN")
        if (!synoptic.isNullOrBlank()) probe("synoptic", false) { http ->
            val r = runSync { SynopticProvider(http, synoptic).fetchStations(point, 25.0) }
            mapOf("ok" to (r.error == null && r.items.isNotEmpty()), "items" to r.items.size, "bytes" to r.bytesReceived, "error" to r.error?.replace(synoptic, "REDACTED"))
        } else results["synoptic"] = mapOf("skipped" to "SYNOPTIC_TOKEN not set")
        val tomorrow = System.getenv("TOMORROW_API_KEY")
        if (!tomorrow.isNullOrBlank()) probe("tomorrow-io", false) { http ->
            val resp = runSync { http.request(TomorrowRequests.get(TomorrowRequests.realtimeUrl(point), tomorrow)) }
            val text = resp.body?.toString(Charsets.UTF_8)
            val v = TomorrowParser.parseRealtime(text)
            mapOf("ok" to (resp.isSuccessful && v?.windSpeed != null), "status" to resp.statusCode, "bytes" to (resp.body?.size ?: 0),
                "error" to if (resp.isSuccessful) null else TomorrowParser.parseError(text, resp.statusCode)?.toString())
        } else results["tomorrow-io"] = mapOf("skipped" to "TOMORROW_API_KEY not set")
        println(ReportJson.write(linkedMapOf("point" to listOf(point.lat, point.lon), "providers" to results, "keyless_failures" to keylessFailures)))
        return keylessFailures
    }
}
