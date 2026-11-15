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

import android.util.Log
import de.timklge.karooheadwind.BuildConfig
import de.timklge.karooheadwind.KarooHeadwindExtension
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.HttpResponseState
import io.hammerhead.karooext.models.OnHttpResponse
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import java.net.URI

/**
 * Sends a request over the Karoo system HTTP path and suspends until it completes. Implemented by
 * [KarooHttp] on the device and by fakes in tests.
 */
fun interface KarooHttpTransport {
    /** Returns the completed state, or throws on a transport error. */
    suspend fun execute(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete
}

/** The real transport: `OnHttpResponse.MakeHttpRequest` (works over Wi-Fi or the phone's Bluetooth link). */
class KarooSystemHttpTransport(private val karooSystem: KarooSystemService) : KarooHttpTransport {
    override suspend fun execute(request: OnHttpResponse.MakeHttpRequest): HttpResponseState.Complete = callbackFlow {
        val listenerId = karooSystem.addConsumer(
            request,
            onEvent = { event: OnHttpResponse ->
                val state = event.state
                if (state is HttpResponseState.Complete) {
                    trySend(state)
                    close()
                }
            },
            onError = { err -> close(KarooHttpException(err)) },
        )
        awaitClose { karooSystem.removeConsumer(listenerId) }
    }.first()
}

class KarooHttpException(message: String) : Exception(message)

/**
 * `core.api.HttpClient` on top of the Karoo HTTP path (SPEC 3, WP8 `KarooHttp`).
 *
 * - 30 s timeout; transport errors and timeouts become [HttpResponse.error] (status 0), never throw.
 * - Response header names are lower-cased.
 * - Every request and its body size are counted in [Diagnostics].
 * - [allowGzip]: when false the `Accept-Encoding: gzip` header the core request builders add is
 *   removed (kill switch in case the Karoo path mangles binary bodies). Either way `decodeBody`
 *   handles plain and gzip bodies. Device check (2026-11-15, Karoo 3 over Wi-Fi): see
 *   [Diagnostics.httpGzipBodies] / [Diagnostics.httpGzipDecodedByTransport].
 */
class KarooHttp(
    private val transport: KarooHttpTransport,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    private val allowGzip: Boolean = true,
    private val userAgent: String = USER_AGENT,
) : HttpClient {

    override suspend fun request(request: HttpRequest): HttpResponse {
        val askedGzip = request.headers.entries.any { it.key.equals("Accept-Encoding", true) && it.value.contains("gzip", true) }
        val headers = LinkedHashMap<String, String>()
        for ((k, v) in request.headers) {
            if (!allowGzip && k.equals("Accept-Encoding", true)) continue
            headers[k] = v
        }
        if (headers.keys.none { it.equals("User-Agent", true) }) headers["User-Agent"] = userAgent
        val host = hostOf(request.url)

        val params = OnHttpResponse.MakeHttpRequest(
            method = request.method,
            url = request.url,
            headers = headers,
            body = request.body,
            waitForConnection = false,
        )
        val response = try {
            val complete = withTimeout(timeoutMs) { transport.execute(params) }
            val body = complete.body
            if (askedGzip && allowGzip && body != null && body.size >= 2) {
                if (body[0] == 0x1f.toByte() && body[1] == 0x8b.toByte()) Diagnostics.httpGzipBodies.incrementAndGet()
                else Diagnostics.httpGzipDecodedByTransport.incrementAndGet()
            }
            HttpResponse(
                statusCode = complete.statusCode,
                headers = complete.headers.mapKeys { it.key.lowercase() },
                body = body,
                // The Karoo reports some failures as a Complete with an error and no status.
                error = complete.error?.takeIf { complete.statusCode !in 100..599 },
            )
        } catch (e: TimeoutCancellationException) {
            Diagnostics.httpTimeouts.incrementAndGet()
            HttpResponse(statusCode = 0, error = "Timeout after ${timeoutMs / 1000} s")
        } catch (e: KarooHttpException) {
            HttpResponse(statusCode = 0, error = "Http error: ${e.message}")
        }
        val bytes = (response.body?.size ?: 0).toLong()
        Diagnostics.recordHttp(host, bytes, error = !response.isSuccessful)
        if (BuildConfig.DEBUG) {
            Log.d(KarooHeadwindExtension.TAG, "http ${request.method} $host ${redactPath(request.url)} -> ${response.statusCode} ${bytes}B${response.error?.let { " err=$it" } ?: ""}")
        }
        return response
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val USER_AGENT = "TrueWind (github.com/kf6999/karoo-truewind)"

        fun hostOf(url: String): String = try {
            URI(url).host ?: "unknown"
        } catch (e: Exception) {
            "unknown"
        }

        /** Path only: query strings can carry API keys and coordinates and are never logged. */
        fun redactPath(url: String): String = try {
            URI(url).path ?: ""
        } catch (e: Exception) {
            ""
        }
    }
}
