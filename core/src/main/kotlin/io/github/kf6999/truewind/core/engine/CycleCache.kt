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

package io.github.kf6999.truewind.core.engine

import io.github.kf6999.truewind.core.api.ForecastSeries
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.Observation
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * The inputs of the last successful fusion cycle, enough to rebuild the fused snapshot after a
 * process restart with no network (written once per successful cycle, restored if younger than
 * [WindEngine.RESTORE_MAX_AGE_MS]).
 */
data class CachedCycle(
    val fetchedAtMs: Long,
    val rider: GeoPoint,
    /** Member id → normalised weight used for the blend. */
    val weights: Map<String, Double>,
    val series: List<ForecastSeries>,
    /** Observations accepted by QC in that cycle (for the residual). */
    val observations: List<Observation>,
    /** Member id → time its series were fetched (members carried over from older cycles keep their own age). */
    val memberFetchedAtMs: Map<String, Long> = emptyMap(),
)

object CycleCacheCodec {
    const val KEY = "truewind.lastCycle"
    private const val VERSION = 1

    fun encode(c: CachedCycle): String = JsonObject(
        mapOf(
            "v" to JsonPrimitive(VERSION),
            "t" to JsonPrimitive(c.fetchedAtMs),
            "r" to JsonArray(listOf(JsonPrimitive(c.rider.lat), JsonPrimitive(c.rider.lon))),
            "w" to JsonObject(c.weights.mapValues { JsonPrimitive(it.value) }),
            "mt" to JsonObject(c.memberFetchedAtMs.mapValues { JsonPrimitive(it.value) }),
            "s" to JsonArray(c.series.map { s ->
                JsonObject(
                    mapOf(
                        "m" to JsonPrimitive(s.memberId),
                        "p" to JsonArray(listOf(JsonPrimitive(s.location.lat), JsonPrimitive(s.location.lon))),
                        "e" to num(s.gridElevationM),
                        "x" to JsonArray(s.steps.map { st ->
                            JsonArray(
                                listOf(
                                    JsonPrimitive(st.timeMs), num(st.wind10m?.u), num(st.wind10m?.v), num(st.gust10m), num(st.temperatureC),
                                    num(st.relativeHumidityPct), num(st.precipitationMm), num(st.surfacePressureHpa),
                                    num(st.sealevelPressureHpa), num(st.cloudCoverPct), st.weatherCode?.let { JsonPrimitive(it) } ?: JsonNull,
                                )
                            )
                        }),
                    )
                )
            }),
            "o" to JsonArray(c.observations.map { o ->
                JsonArray(
                    listOf(
                        JsonPrimitive(o.stationId), JsonPrimitive(o.source), JsonPrimitive(o.location.lat), JsonPrimitive(o.location.lon),
                        num(o.elevationM), JsonPrimitive(o.timeMs), num(o.wind?.u), num(o.wind?.v), num(o.speed), num(o.gust),
                        JsonPrimitive(o.variableDirection), JsonPrimitive(o.sensorHeightM), num(o.temperatureC), JsonPrimitive(o.reliability),
                        o.network?.let { JsonPrimitive(it) } ?: JsonNull,
                    )
                )
            }),
        )
    ).toString()

    /** Null for anything that is not a version-1 cache (never throws). */
    fun decode(text: String?): CachedCycle? {
        if (text.isNullOrBlank()) return null
        return try {
            val root = Json.parseToJsonElement(text).jsonObject
            if (root["v"]?.jsonPrimitive?.intOrNull != VERSION) return null
            val series = root["s"]!!.jsonArray.map { e ->
                val s = e.jsonObject
                val p = s["p"]!!.jsonArray
                ForecastSeries(
                    memberId = s["m"]!!.jsonPrimitive.content,
                    location = GeoPoint(p[0].dbl()!!, p[1].dbl()!!),
                    gridElevationM = s["e"].dbl(),
                    steps = s["x"]!!.jsonArray.map { x ->
                        val a = x.jsonArray
                        val u = a[1].dbl()
                        val v = a[2].dbl()
                        ForecastStep(
                            timeMs = a[0].jsonPrimitive.longOrNull!!,
                            wind10m = if (u != null && v != null) WindVector(u, v) else null,
                            gust10m = a[3].dbl(), temperatureC = a[4].dbl(), relativeHumidityPct = a[5].dbl(), precipitationMm = a[6].dbl(),
                            surfacePressureHpa = a[7].dbl(), sealevelPressureHpa = a[8].dbl(), cloudCoverPct = a[9].dbl(),
                            weatherCode = a[10].let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull },
                        )
                    },
                )
            }
            val obs = root["o"]!!.jsonArray.map { e ->
                val a = e.jsonArray
                val u = a[6].dbl()
                val v = a[7].dbl()
                Observation(
                    stationId = a[0].jsonPrimitive.content, source = a[1].jsonPrimitive.content,
                    location = GeoPoint(a[2].dbl()!!, a[3].dbl()!!), elevationM = a[4].dbl(), timeMs = a[5].jsonPrimitive.longOrNull!!,
                    wind = if (u != null && v != null) WindVector(u, v) else null, speed = a[8].dbl(), gust = a[9].dbl(),
                    variableDirection = a[10].jsonPrimitive.booleanOrNull ?: false, sensorHeightM = a[11].dbl() ?: 10.0,
                    temperatureC = a[12].dbl(), reliability = a[13].dbl() ?: 1.0,
                    network = a[14].let { if (it is JsonNull) null else it.jsonPrimitive.content },
                )
            }
            val rider = root["r"]!!.jsonArray
            CachedCycle(
                fetchedAtMs = root["t"]!!.jsonPrimitive.longOrNull!!,
                rider = GeoPoint(rider[0].dbl()!!, rider[1].dbl()!!),
                weights = root["w"]!!.jsonObject.mapValues { it.value.jsonPrimitive.doubleOrNull ?: 0.0 },
                series = series,
                observations = obs,
                memberFetchedAtMs = root["mt"]?.jsonObject?.mapValues { it.value.jsonPrimitive.longOrNull ?: 0L }.orEmpty(),
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun num(x: Double?): JsonElement = if (x == null || !x.isFinite()) JsonNull else JsonPrimitive(x)

    private fun JsonElement?.dbl(): Double? = if (this == null || this is JsonNull) null else jsonPrimitive.doubleOrNull
}
