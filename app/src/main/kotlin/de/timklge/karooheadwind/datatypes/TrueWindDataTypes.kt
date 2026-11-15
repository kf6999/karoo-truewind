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

package de.timklge.karooheadwind.datatypes

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.TextUnitType
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceRemoteViews
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.compose.ui.unit.dp
import androidx.glance.text.FontFamily
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.WindExtras
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.pipeline.WindSnapshot
import de.timklge.karooheadwind.throttle
import de.timklge.karooheadwind.util.msInWindUnit
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.DataPoint
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.ShowCustomStreamState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UpdateGraphicConfig
import io.hammerhead.karooext.models.UserProfile
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Inputs of a TrueWind numeric field. */
data class FieldInputs(val snapshot: WindSnapshot, val settings: HeadwindSettings, val profile: UserProfile) {
    val isImperial: Boolean get() = profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
    fun wind(ms: Double): Double = msInWindUnit(ms, settings.getWindUnit(isImperial))
}

/**
 * A numeric TrueWind field (SPEC 5) mapped from the shared snapshot. Emits only when the value
 * moves by [threshold] display units (SPEC 3 rule 4); the Karoo renders numeric fields itself.
 */
open class SnapshotNumericDataType(
    private val windRepository: WindRepository,
    typeId: String,
    private val threshold: Double,
    private val formatDataTypeId: String?,
    private val compute: (FieldInputs) -> Double?,
) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, typeId) {

    fun valueFor(inputs: FieldInputs): Double? = compute(inputs)?.takeIf { it.isFinite() }

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            val gate = EmitGate(threshold)
            val refreshRate = windRepository.refreshRateMs()
            combine(windRepository.readySnapshot, windRepository.settings, windRepository.sensorHub.userProfile) { s, st, p ->
                valueFor(FieldInputs(s, st, p))
            }.throttle(refreshRate).collect { value ->
                if (gate.shouldEmit(value)) {
                    Diagnostics.countEmit(dataTypeId)
                    emitter.onNext(value?.let { StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))) } ?: StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        if (formatDataTypeId != null) emitter.onNext(UpdateGraphicConfig(formatDataTypeId = formatDataTypeId))
    }
}

/** The SPEC 5 numeric fields added by TrueWind. Type ids are registered in `extension_info.xml`. */
object TrueWindFields {
    const val CROSSWIND = "crosswind"
    const val CONFIDENCE = "windConfidence"
    const val SOURCE = "windSource"
    const val MEASURED_HEADWIND = "measuredHeadwind"
    const val AIR_DENSITY = "airDensity"
    const val FEELS_LIKE = "feelsLike"
    const val RAIN_IN = "rainIn"
    const val LAST_UPDATE = "lastUpdate"
    const val NEXT_UPDATE = "nextUpdate"
    const val STATION_DISTANCE = "stationDistance"
    const val MAX_WIND = "maxWindRide"

    val NUMERIC_IDS = listOf(CROSSWIND, CONFIDENCE, MEASURED_HEADWIND, AIR_DENSITY, FEELS_LIKE, RAIN_IN, LAST_UPDATE, NEXT_UPDATE, STATION_DISTANCE, MAX_WIND)

    /** Value of a field; null = not available ("--"). Exposed for tests. */
    fun value(typeId: String, i: FieldInputs, nowMs: Long = i.snapshot.state.timeMs): Double? {
        val s = i.snapshot
        val st = s.state
        return when (typeId) {
            // Signed, + from the right; headline wind (rider height unless 10 m is chosen).
            CROSSWIND -> {
                val w = s.headline ?: return null
                val h = st.riderHeadingDeg ?: return null
                i.wind(w.crosswind(h))
            }
            CONFIDENCE -> if (st.model10m == null || s.extras.upstreamMode) null else st.confidence.toDouble()
            MEASURED_HEADWIND -> s.extras.measuredHeadwind?.let { i.wind(it) }
            AIR_DENSITY -> if (st.model10m == null && st.weather == null) null else st.airDensity
            FEELS_LIKE -> s.extras.feelsLikeC ?: st.weather?.temperatureC
            RAIN_IN -> when (val r = s.extras.rainInMin) {
                null -> null // no rain in the next 2 h
                WindExtras.RAIN_UNKNOWN -> null
                else -> r.toDouble()
            }
            LAST_UPDATE -> s.extras.lastUpdateMs?.let { ((nowMs - it) / 60_000.0).coerceAtLeast(0.0).let { m -> Math.floor(m) } }
                ?: st.ageMs?.let { Math.floor(it / 60_000.0) }
            NEXT_UPDATE -> s.extras.nextUpdateMs?.let { Math.ceil(((it - nowMs) / 60_000.0).coerceAtLeast(0.0)) }
            STATION_DISTANCE -> st.sources.nearestStationDistanceKm?.let { if (i.isImperial) it / 1.609344 else it }
            MAX_WIND -> s.extras.maxWindThisRide?.let { i.wind(it) }
            else -> null
        }
    }

    private fun threshold(typeId: String): Double = when (typeId) {
        CROSSWIND, MEASURED_HEADWIND, MAX_WIND -> EmitGate.SPEED_THRESHOLD
        AIR_DENSITY -> 0.005
        FEELS_LIKE, STATION_DISTANCE -> 0.5
        else -> 0.0
    }

    private fun format(typeId: String): String? = when (typeId) {
        CROSSWIND, MEASURED_HEADWIND, MAX_WIND -> DataType.Type.INTENSITY_FACTOR
        FEELS_LIKE -> DataType.Type.TEMPERATURE
        AIR_DENSITY -> DataType.Type.INTENSITY_FACTOR
        else -> null
    }

    fun numeric(repo: WindRepository): List<DataTypeImpl> = NUMERIC_IDS.map { id ->
        SnapshotNumericDataType(repo, id, threshold(id), format(id)) { i -> value(id, i, System.currentTimeMillis()) }
    }
}

/** "Wind source" graphical text field, e.g. `HRRR+NBM · KSQL 6 km · 4 min`. */
@OptIn(ExperimentalGlanceRemoteViewsApi::class)
class WindSourceDataType(private val windRepository: WindRepository) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, TrueWindFields.SOURCE) {
    private val glance = GlanceRemoteViews()

    override fun startStream(emitter: Emitter<StreamState>) {
        // The value is text; the stream only signals availability (confidence 0..100) for the Karoo.
        val job = CoroutineScope(Dispatchers.IO).launch {
            windRepository.readySnapshot.map { it.state.confidence.toDouble() }.distinctUntilChanged().collect {
                Diagnostics.countEmit(dataTypeId)
                emitter.onNext(StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))))
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val configJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))
            awaitCancellation()
        }
        val viewJob = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(ShowCustomStreamState("", null))
            val texts = if (config.preview) {
                kotlinx.coroutines.flow.flowOf("HRRR+NBM · KSQL 6 km · 4 min" to true)
            } else {
                combine(windRepository.readySnapshot.map { sourceLabel(it) }.distinctUntilChanged(), windRepository.sensorHub.isVisible(dataTypeId)) { t, v -> t to v }
            }
            // Only while visible, only on change, at most once per second.
            texts.filter { it.second }.distinctUntilChanged().throttle(1_000L).collect { (text, _) ->
                val result = glance.compose(context, DpSize.Unspecified) {
                    Box(modifier = GlanceModifier.fillMaxSize().padding(2.dp), contentAlignment = Alignment.Center) {
                        Text(text, style = TextStyle(color = ColorProvider(Color.Black, Color.White), fontFamily = FontFamily.Monospace,
                            textAlign = TextAlign.Center, fontSize = TextUnit((config.textSize * 0.45f).coerceAtLeast(12f), TextUnitType.Sp)))
                    }
                }
                emitter.updateViewCounted(dataTypeId, result.remoteViews)
            }
        }
        emitter.setCancellable {
            configJob.cancel()
            viewJob.cancel()
        }
    }

    companion object {
        fun sourceLabel(s: WindSnapshot): String = if (s.extras.upstreamMode) {
            s.state.sources.models.joinToString("+").ifEmpty { "--" }
        } else s.extras.sourceText.ifEmpty { "--" } + if (s.extras.band != null) " · ${s.state.confidence}%" else ""
    }
}
