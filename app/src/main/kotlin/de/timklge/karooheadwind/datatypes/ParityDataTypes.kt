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

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.TypedValue
import android.widget.RemoteViews
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.R
import de.timklge.karooheadwind.pipeline.DebugOverrides
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.ParityHub
import de.timklge.karooheadwind.pipeline.ParityRuntime
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.util.msInWindUnit
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.airquality.UsAqiCategory
import io.github.kf6999.truewind.core.providers.radar.CachedFrame
import io.github.kf6999.truewind.core.providers.radar.RadarFramePolicy
import io.github.kf6999.truewind.core.providers.radar.TilePlan
import io.github.kf6999.truewind.core.providers.radar.TilePlanner
import io.github.kf6999.truewind.core.time.Clock
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** WP13-16 field ids (registered in `extension_info.xml`). */
object ParityFields {
    const val RADAR = "radar"
    const val STORM = "stormDistance"
    const val RAIN_BAR = "rainBar"
    const val AQI = "aqi"
    const val PM25 = "pm25"
    const val OZONE = "ozone"
    const val DEW_POINT = "dewPoint"
    const val VISIBILITY = "visibility"
    const val SOLAR = "solarRadiation"
    const val WIND_CHILL = "windChill"

    val AIR_QUALITY_IDS = setOf(AQI, PM25, OZONE)
    val ALL_IDS = listOf(RADAR, STORM, RAIN_BAR, AQI, PM25, OZONE, DEW_POINT, VISIBILITY, SOLAR, WIND_CHILL)

    /** Snapshot-backed numeric fields (forecast extras). */
    fun snapshotValue(typeId: String, i: FieldInputs): Double? {
        val x = i.snapshot.extras
        return when (typeId) {
            DEW_POINT -> x.dewPointC
            VISIBILITY -> x.visibilityM
            SOLAR -> x.solarRadiationWm2
            WIND_CHILL -> x.windChillC
            else -> null
        }
    }

    fun all(repo: WindRepository, hub: () -> ParityHub?): List<DataTypeImpl> = listOf(
        RadarDataType(repo, hub),
        RainBarDataType(repo, hub),
        AqiDataType(repo, hub),
        HubNumericDataType(PM25, 0.5, null, hub) { h -> h.airQuality.mapValue { it?.pm25 } },
        HubNumericDataType(OZONE, 1.0, null, hub) { h -> h.airQuality.mapValue { it?.ozone } },
        // Estimate (radar cores, METAR thunder, NWS warnings), not lightning. Metres for the DISTANCE format.
        HubNumericDataType(STORM, 200.0, DataType.Type.DISTANCE, hub) { h -> h.storm.mapValue { it?.distanceKm?.times(1000.0) } },
        SnapshotNumericDataType(repo, DEW_POINT, 0.3, DataType.Type.TEMPERATURE) { snapshotValue(DEW_POINT, it) },
        SnapshotNumericDataType(repo, VISIBILITY, 100.0, DataType.Type.DISTANCE) { snapshotValue(VISIBILITY, it) },
        SnapshotNumericDataType(repo, SOLAR, 5.0, null) { snapshotValue(SOLAR, it) },
        SnapshotNumericDataType(repo, WIND_CHILL, 0.3, DataType.Type.TEMPERATURE) { snapshotValue(WIND_CHILL, it) },
    )
}

private fun <T, R> kotlinx.coroutines.flow.StateFlow<T>.mapValue(f: (T) -> R): Flow<R> =
    kotlinx.coroutines.flow.flow { collect { emit(f(it)) } }

/** Waits for the parity hub (created in the extension's onCreate). */
private suspend fun awaitHub(hub: () -> ParityHub?): ParityHub {
    while (true) {
        hub()?.let { return it }
        delay(500)
    }
}

/** Numeric field backed by a [ParityHub] state (PM2.5, ozone, storm distance). Emit on change only. */
class HubNumericDataType(
    typeId: String,
    private val threshold: Double,
    private val formatDataTypeId: String?,
    private val hub: () -> ParityHub?,
    private val source: (ParityHub) -> Flow<Double?>,
) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, typeId) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(StreamState.NotAvailable)
            val gate = EmitGate(threshold)
            gate.shouldEmit(null)
            source(awaitHub(hub)).collect { v ->
                val value = v?.takeIf { it.isFinite() }
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

/** US AQI with the EPA category colour as background (WP15). Stream value: US AQI. */
class AqiDataType(private val repo: WindRepository, private val hub: () -> ParityHub?) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, ParityFields.AQI) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(StreamState.NotAvailable)
            var last: Int? = Int.MIN_VALUE
            awaitHub(hub).airQuality.collect { aq ->
                val v = aq?.usAqi
                if (v != last) {
                    last = v
                    Diagnostics.countEmit(dataTypeId)
                    emitter.onNext(v?.let { StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it.toDouble()))) } ?: StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = true))
            emitter.onNext(ShowCustomStreamState("", null))
            val values: Flow<Int?> = if (config.preview) flowOf(42) else awaitHub(hub).airQuality.mapValue { it?.usAqi }
            var last: Int? = Int.MIN_VALUE
            values.collect { aqi ->
                if (aqi == last) return@collect
                last = aqi
                val rv = RemoteViews(context.packageName, R.layout.truewind_text_view)
                val cat = aqi?.let { UsAqiCategory.of(it) }
                val bg = aqiColor(cat)
                rv.setInt(R.id.truewind_root, "setBackgroundColor", bg ?: Color.TRANSPARENT)
                val fg = if (bg == null) (if (context.isNightMode()) Color.WHITE else Color.BLACK) else aqiTextColor(cat)
                rv.setTextViewText(R.id.truewind_value, aqi?.toString() ?: "--")
                rv.setTextColor(R.id.truewind_value, fg)
                rv.setTextViewTextSize(R.id.truewind_value, TypedValue.COMPLEX_UNIT_SP, config.textSize.toFloat().coerceAtLeast(14f))
                rv.setTextViewText(R.id.truewind_label, cat?.label ?: "")
                rv.setTextColor(R.id.truewind_label, fg)
                emitter.updateViewCounted(dataTypeId, rv)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    companion object {
        /** EPA AQI colours. */
        fun aqiColor(c: UsAqiCategory?): Int? = when (c) {
            null -> null
            UsAqiCategory.GOOD -> Color.rgb(0, 228, 0)
            UsAqiCategory.MODERATE -> Color.rgb(255, 255, 0)
            UsAqiCategory.UNHEALTHY_FOR_SENSITIVE -> Color.rgb(255, 126, 0)
            UsAqiCategory.UNHEALTHY -> Color.rgb(255, 0, 0)
            UsAqiCategory.VERY_UNHEALTHY -> Color.rgb(143, 63, 151)
            UsAqiCategory.HAZARDOUS -> Color.rgb(126, 0, 35)
        }

        fun aqiTextColor(c: UsAqiCategory?): Int = when (c) {
            UsAqiCategory.UNHEALTHY, UsAqiCategory.VERY_UNHEALTHY, UsAqiCategory.HAZARDOUS -> Color.WHITE
            else -> Color.BLACK
        }
    }
}

/** 2x1 rain bars for the next 2 h (WP14). Stream value: peak rain rate, mm/h (0 = dry). */
class RainBarDataType(private val repo: WindRepository, private val hub: () -> ParityHub?) : DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, ParityFields.RAIN_BAR) {
    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(StreamState.NotAvailable)
            var last: Double? = Double.NaN
            awaitHub(hub).rainBars.collect { m ->
                val v = m?.maxMmPerHour
                if (v != last) {
                    last = v
                    Diagnostics.countEmit(dataTypeId)
                    emitter.onNext(v?.let { StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))) } ?: StreamState.NotAvailable)
                }
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            val (w, h) = bitmapSizeFor(config.viewSize.first, config.viewSize.second)
            val dark = context.isNightMode()
            if (config.preview) {
                val now = System.currentTimeMillis()
                val demo = io.github.kf6999.truewind.core.alerts.RainBars.build(now, emptyList(), (0 until 120).map {
                    io.github.kf6999.truewind.core.alerts.PrecipStep(now + it * 60_000L, 1, if (it in 25..70) (it - 20) / 300.0 else 0.0)
                })
                show(context, emitter, RainBarRenderer.draw(demo, w, h, dark), null)
                return@launch
            }
            val h0 = awaitHub(hub)
            var prev: Bitmap? = null
            var lastModel: Any? = Unit
            while (true) {
                val visible = DebugOverrides.forceVisible.value || isVisible(repo, dataTypeId)
                val model = h0.rainBars.value
                if (visible && model !== lastModel) {
                    lastModel = model
                    val imperial = repo.sensorHub.userProfile.replayCache.firstOrNull()?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
                    val bmp = RainBarRenderer.draw(model, w, h, dark, imperial)
                    show(context, emitter, bmp, prev)
                    prev = bmp
                }
                delay(if (visible) 5_000L else 10_000L)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    private fun show(context: Context, emitter: ViewEmitter, bmp: Bitmap, recycle: Bitmap?) {
        val rv = RemoteViews(context.packageName, R.layout.truewind_image_view)
        rv.setImageViewBitmap(R.id.truewind_image, bmp)
        emitter.updateViewCounted(dataTypeId, rv)
        recycle?.recycle()
    }
}

/** Visible on the current ride page (from the shared page flow; non-suspending). */
internal fun isVisible(repo: WindRepository, dataTypeId: String): Boolean =
    repo.sensorHub.activeRidePage.replayCache.firstOrNull()?.page?.elements?.any { it.dataTypeId == dataTypeId } == true

/** Radar animation steps (pure): last [FRAMES] cached frames at 1 frame/s, then hold the newest [HOLD_TICKS] ticks. */
object RadarAnimation {
    const val FRAMES = 3
    const val HOLD_TICKS = 5

    /** (frameIdx, holdTicks) -> next state, called once per 1 s tick with [count] >= 2 frames. */
    fun next(frameIdx: Int, holdTicks: Int, count: Int): Pair<Int, Int> =
        if (frameIdx >= count - 1) { if (holdTicks + 1 >= HOLD_TICKS) 0 to 0 else frameIdx to holdTicks + 1 } else (frameIdx + 1) to 0
}

/** Per-view zoom state for the radar field; cycled by [RadarZoomReceiver]. */
object RadarZoomRegistry {
    val ZOOMS = listOf(6, 7, 8)
    const val DEFAULT_ZOOM = 7
    private val next = AtomicInteger(1)
    val zooms = ConcurrentHashMap<Int, MutableStateFlow<Int>>()

    fun register(): Pair<Int, MutableStateFlow<Int>> {
        val id = next.getAndIncrement()
        val f = MutableStateFlow(DEFAULT_ZOOM)
        zooms[id] = f
        return id to f
    }

    fun unregister(id: Int) { zooms.remove(id) }

    fun cycle(id: Int) {
        val f = zooms[id] ?: return
        f.value = nextZoom(f.value)
    }

    fun nextZoom(z: Int): Int = ZOOMS[(ZOOMS.indexOf(z) + 1).mod(ZOOMS.size)]
}

/** Tap on the radar field: next zoom (6 -> 7 -> 8 -> 6). Not exported; only our PendingIntent reaches it. */
class RadarZoomReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        if (id >= 0) RadarZoomRegistry.cycle(id)
    }

    companion object {
        const val ACTION = "io.github.kf6999.truewind.RADAR_ZOOM"
        const val EXTRA_ID = "id"
    }
}

/**
 * Radar field (WP13): RainViewer tiles around the rider (at most 4, ~100 KB budget), fire perimeters,
 * rider dot + heading, wind arrow. Refreshes per [RadarFramePolicy] (10 min, never while hidden),
 * lined up with the engine's fetch cycle when possible; animates the cached frames at 1 frame/s
 * only while visible. Stream value: age of the newest radar frame, minutes.
 */
class RadarDataType(private val repo: WindRepository, private val hub: () -> ParityHub?, private val clock: Clock = Clock.SYSTEM) :
    DataTypeImpl(KarooHeadwindExtension.EXTENSION_ID, ParityFields.RADAR) {

    override fun startStream(emitter: Emitter<StreamState>) {
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(StreamState.NotAvailable)
            val h = awaitHub(hub)
            var last: Double? = Double.NaN
            while (true) {
                val v = h.radar.latestFrameTimeMs?.let { Math.floor((clock.nowMs() - it) / 60_000.0).coerceAtLeast(0.0) }
                if (v != last) {
                    last = v
                    Diagnostics.countEmit(dataTypeId)
                    emitter.onNext(v?.let { StreamState.Streaming(DataPoint(dataTypeId, mapOf(DataType.Field.SINGLE to it))) } ?: StreamState.NotAvailable)
                }
                delay(30_000L)
            }
        }
        emitter.setCancellable { job.cancel() }
    }

    override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) {
        val (id, zoom) = RadarZoomRegistry.register()
        val job = CoroutineScope(Dispatchers.IO).launch {
            emitter.onNext(UpdateGraphicConfig(showHeader = false))
            emitter.onNext(ShowCustomStreamState("", null))
            val (w, h) = bitmapSizeFor(config.viewSize.first, config.viewSize.second)
            val dark = context.isNightMode()
            val tap = PendingIntent.getBroadcast(
                context, id,
                Intent(RadarZoomReceiver.ACTION).setClass(context, RadarZoomReceiver::class.java).putExtra(RadarZoomReceiver.EXTRA_ID, id),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            if (config.preview) {
                show(context, emitter, RadarRenderer.message(w, h, "Radar", dark), tap)
                return@launch
            }
            RadarView(context, emitter, awaitHub(hub), w, h, dark, tap, zoom).run()
        }
        emitter.setCancellable {
            job.cancel()
            RadarZoomRegistry.unregister(id)
        }
    }

    private fun show(context: Context, emitter: ViewEmitter, bmp: Bitmap, tap: PendingIntent) {
        val rv = RemoteViews(context.packageName, R.layout.truewind_image_view)
        rv.setImageViewBitmap(R.id.truewind_image, bmp)
        rv.setOnClickPendingIntent(R.id.truewind_root, tap)
        emitter.updateViewCounted(dataTypeId, rv)
    }

    private inner class RadarView(
        val context: Context,
        val emitter: ViewEmitter,
        val hub: ParityHub,
        val w: Int,
        val h: Int,
        val dark: Boolean,
        val tap: PendingIntent,
        val zoom: MutableStateFlow<Int>,
    ) {
        val policy = RadarFramePolicy<ByteArray>(clock, keepFrames = RadarAnimation.FRAMES)
        var plan: TilePlan? = null
        var planZoom = -1
        var bases: List<Pair<Long, Bitmap>> = emptyList()
        var basesKey: Any? = null
        var shown: Bitmap? = null
        var frameIdx = 0
        var holdTicks = 0
        var dueSinceMs: Long? = null

        suspend fun run() {
            try {
                while (true) {
                    tick()
                    delay(1_000L)
                }
            } finally {
                bases.forEach { it.second.recycle() }
                shown?.recycle()
            }
        }

        private suspend fun tick() {
            val visible = DebugOverrides.forceVisible.value || isVisible(repo, dataTypeId)
            if (!visible) { dueSinceMs = null; return }
            val pos = hub.riderPosition()
            if (pos == null) {
                if (shown == null) display(RadarRenderer.message(w, h, "Waiting for GPS", dark))
                return
            }
            // Keep the tile plan while the rider stays near the centre (no re-compositing every second).
            val p0 = plan
            val z = zoom.value
            if (p0 == null || planZoom != z || drift(p0, pos) > 0.25) {
                plan = TilePlanner.plan(pos, z, w, h)
                planZoom = z
            }
            val p = plan!!
            policy.setTiles(p.tiles.map { it.tile })

            // Refresh: due per policy (visible), and either the engine just fetched (one radio wake),
            // nothing is shown yet, or we have waited a minute for the next engine cycle.
            val now = clock.nowMs()
            if (policy.shouldRefresh(true)) {
                if (dueSinceMs == null) dueSinceMs = now
                val cycleEnd = hub.lastCycleEndMs.value
                val aligned = cycleEnd != null && now - cycleEnd in 0..20_000L
                if (policy.latestFrame == null || aligned || now - dueSinceMs!! >= 60_000L) {
                    val core = if (hub.needs?.stormRadar == true) pos else null
                    hub.radar.refresh(p, policy, computeCoreAt = core)
                    dueSinceMs = null
                }
            } else dueSinceMs = null

            val frames = policy.cachedFrames.filter { it.tiles.isNotEmpty() }
            if (frames.isEmpty()) {
                if (shown == null || basesKey != null) display(RadarRenderer.message(w, h, "Radar loading", dark))
                basesKey = null
                return
            }
            val key = Triple(frames.map { it.frame.timeMs to it.tiles.size }, p, dark)
            if (key != basesKey) {
                bases.forEach { it.second.recycle() }
                bases = frames.map { f: CachedFrame<ByteArray> -> f.frame.timeMs to RadarRenderer.composeBase(p, f.tiles, dark) }
                basesKey = key
                frameIdx = bases.size - 1
                holdTicks = 0
            }
            // Animation: cached frames (never fetched for this), oldest -> newest at 1 frame/s, hold the newest ~5 s.
            if (bases.size > 1) {
                val st = RadarAnimation.next(frameIdx, holdTicks, bases.size)
                frameIdx = st.first; holdTicks = st.second
            }
            val (frameTime, base) = bases[frameIdx.coerceIn(0, bases.size - 1)]
            val snap = repo.snapshot.value
            val profile = repo.sensorHub.userProfile.replayCache.firstOrNull()
            val settings = repo.settings.replayCache.firstOrNull()
            val wind = snap.headline
            val windText = if (wind != null && settings != null && profile != null) {
                val unit = settings.getWindUnit(profile.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL)
                String.format(Locale.US, "%.0f %s", msInWindUnit(wind.speed, unit), unit.unitDisplay)
            } else null
            val age = ((now - frameTime) / 60_000L).coerceAtLeast(0)
            // Rider dot at the true position on the anchored plan.
            val v = p.geoToView(pos)
            val drawPlan = p.copy(riderViewX = v.x, riderViewY = v.y)
            val overlay = RadarOverlay(
                headingDeg = snap.state.riderHeadingDeg,
                windFromDeg = wind?.takeIf { it.speed > 0.3 }?.directionFromDeg,
                windText = windText,
                label = "z$planZoom · ${age}m${if (bases.size > 1) " ${frameIdx + 1}/${bases.size}" else ""}",
                fires = hub.fires.value, // perimeters the wildfire alert already fetched (US); none -> nothing drawn
            )
            display(RadarRenderer.drawOverlay(base, drawPlan, overlay, dark))
        }

        /** Rider offset from the plan centre as a fraction of the shorter view side. */
        private fun drift(p: TilePlan, pos: GeoPoint): Double {
            val v = p.geoToView(pos)
            val dx = v.x - p.viewWidth / 2.0
            val dy = v.y - p.viewHeight / 2.0
            return Math.hypot(dx, dy) / minOf(p.viewWidth, p.viewHeight)
        }

        private fun display(bmp: Bitmap) {
            val old = shown
            show(context, emitter, bmp, tap)
            shown = bmp
            old?.recycle()
        }
    }
}
