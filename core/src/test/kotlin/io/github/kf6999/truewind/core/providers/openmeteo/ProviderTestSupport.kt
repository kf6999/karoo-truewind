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

package io.github.kf6999.truewind.core.providers.openmeteo

import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.api.HttpRequest
import io.github.kf6999.truewind.core.api.HttpResponse
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine

/** Loads a recorded fixture from `src/test/resources/fixtures/`. */
fun fixture(path: String): String =
    requireNotNull(ProviderTestSupport::class.java.classLoader.getResource("fixtures/$path")) { "missing fixture $path" }
        .readText()

/**
 * Runs a suspend block to completion on the calling thread. The fakes never actually suspend, so
 * no coroutine library is needed in tests.
 */
fun <T> runSuspend(block: suspend () -> T): T {
    var outcome: Result<T>? = null
    block.startCoroutine(Continuation(EmptyCoroutineContext) { outcome = it })
    return requireNotNull(outcome) { "block suspended; fakes must not suspend" }.getOrThrow()
}

/** Scripted fake: answers each request with the next response from [responder]; records requests. */
class FakeHttpClient(private val responder: (HttpRequest, Int) -> HttpResponse) : HttpClient {
    val requests = ArrayList<HttpRequest>()

    override suspend fun request(request: HttpRequest): HttpResponse {
        requests += request
        return responder(request, requests.size - 1)
    }

    companion object {
        fun sequence(vararg responses: HttpResponse) = FakeHttpClient { _, i -> responses[minOf(i, responses.size - 1)] }
    }
}

fun ok(body: String) = HttpResponse(200, body = body.toByteArray())

fun status(code: Int, body: String = "") = HttpResponse(code, body = body.toByteArray())

object ProviderTestSupport
