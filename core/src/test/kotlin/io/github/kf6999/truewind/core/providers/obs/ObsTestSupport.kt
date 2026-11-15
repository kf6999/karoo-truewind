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

package io.github.kf6999.truewind.core.providers.obs

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse

/** Loads a recorded response from `src/test/resources`. */
fun fixture(path: String): ByteArray =
    requireNotNull(ObsTestSupport::class.java.classLoader.getResource(path)) { "missing fixture $path" }.readBytes()

object ObsTestSupport

/**
 * Fake [HttpClient] answering by URL prefix / substring match. Records every request. Unmatched
 * URLs get a 404.
 */
class FakeHttpClient(private val routes: MutableList<Pair<(String) -> Boolean, () -> HttpResponse>> = mutableListOf()) : HttpClient {
    val requests = mutableListOf<HttpRequest>()

    fun on(urlContains: String, response: () -> HttpResponse): FakeHttpClient {
        routes += { url: String -> url.contains(urlContains) } to response
        return this
    }

    fun onFixture(urlContains: String, path: String, status: Int = 200): FakeHttpClient =
        on(urlContains) { HttpResponse(status, body = fixture(path)) }

    override suspend fun request(request: HttpRequest): HttpResponse {
        requests += request
        return routes.firstOrNull { it.first(request.url) }?.second?.invoke() ?: HttpResponse(404, body = ByteArray(0))
    }
}
