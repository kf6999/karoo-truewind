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
import de.timklge.karooheadwind.ForecastRecord
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.UpcomingRoute
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import io.github.kf6999.truewind.core.engine.CycleContext
import io.github.kf6999.truewind.core.engine.CycleReport
import io.github.kf6999.truewind.core.engine.WindEngine
import io.github.kf6999.truewind.core.fusion.FusedSample
import io.github.kf6999.truewind.core.fusion.KeyValueStore
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.time.Clock
import io.github.kf6999.truewind.core.virtualwind.RiderWindEstimator
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.max

/** Creates the [WindEngine] for a settings combination (providers depend on keys and layers). */
fun interface EngineFactory {
    fun create(settings: HeadwindSettings): WindEngine
}

/** Debug-only overrides (set by the debug provisioning receiver; never set in release builds). */
object DebugOverrides {
    /** Position used when there is no GPS fix (indoor device checks). */
    @Volatile var position: GeoPoint? = null
}

/** Handles the app UI and the debug receiver use to reach the running extension (same process). */
object TrueWindRuntime {
    @Volatile var pipeline: TrueWindPipeline? = null
    /** The running extension's repository (null while the extension service is not running). */
    val repositoryFlow = MutableStateFlow<WindRepository?>(null)
    var repository: WindRepository?
        get() = repositoryFlow.value
        set(value) { repositoryFlow.value = value }
}

/** Whether the app's own screen is in the foreground (counts as "visible", SPEC 4.1). */
object AppVisibility {
    private val started = java.util.concurrent.atomic.AtomicInteger(0)
    val screenOpen = MutableStateFlow(false)

    /** Ref-counted so a recreated activity's late onStop cannot hide the new instance. */
    fun onStart() { screenOpen.value = started.incrementAndGet() > 0 }
    fun onStop() { screenOpen.value = started.updateAndGet { maxOf(0, it - 1) } > 0 }
}

/**
 * Start/stop rule for the 1 Hz tick and the fetch loop (SPEC 3 rule 3, 4.1): only in TrueWind mode
 * with the app set up, and only while a TrueWind field is on the active ride page, the app screen
 * is open, or a ride is recording. Otherwise nothing runs.
 */
object TickPolicy {
    fun shouldRun(settings: HeadwindSettings?, fieldVisible: Boolean, screenOpen: Boolean, recording: Boolean): Boolean =
        settings != null && !settings.upstreamCompatibleMode && settings.welcomeDialogAccepted &&
            (fieldVisible || screenOpen || recording)

    /** True when any element of the page is one of this extension's data types. */
    fun anyFieldVisible(dataTypeIds: List<String>, extensionId: String = KarooHeadwindExtension.EXTENSION_ID): Boolean {
        val prefix = DataType.dataTypeId(extensionId, "")
        return dataTypeIds.any { it.startsWith(prefix) }
    }

    /** Settings that require a new [WindEngine] when they change. */
    fun engineKey(s: HeadwindSettings): List<Any> = listOf(
        s.refreshIntervalMin, s.layerEnsemble, s.layerObservations, s.synopticToken, s.tomorrowApiKey,
        s.weatherProvider, s.openWeatherMapApiKey,
    )

    /** Rider position as sent to providers: rounded to the privacy grid unless "Exact". */
    fun roundForFetch(p: GeoPoint, settings: HeadwindSettings): GeoPoint {
        val km = settings.roundLocationTo.km
        if (km <= 0) return p
        val r = GpsCoordinates(p.lat, p.lon).round(km.toDouble())
        return GeoPoint(r.lat, r.lon)
    }
}

/**
 * Wires the core [WindEngine] and [RiderWindEstimator] to the Karoo (SPEC 3): one fetch loop
 * driving [WindEngine.runCycleIfDue] and a 1 Hz tick producing [snapshots]. Both run only while
 * [TickPolicy.shouldRun] holds.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TrueWindPipeline(
    private val scope: CoroutineScope,
    private val hub: SensorHub,
    private val settingsFlow: Flow<HeadwindSettings>,
    private val forecastRecordFlow: Flow<ForecastRecord?>,
    private val lastKnownPosition: suspend () -> GpsCoordinates?,
    private val engineFactory: EngineFactory,
    private val store: KeyValueStore,
    private val clock: Clock = Clock.SYSTEM,
    private val screenOpen: Flow<Boolean> = AppVisibility.screenOpen,
    private val bikeKeyFlow: Flow<String> = flowOf(DEFAULT_BIKE),
    private val barometer: Flow<Double?> = flowOf(null),
    private val tickIntervalMs: Long = 1_000L,
    private val maxFetchWaitMs: Long = 60_000L,
) {
    private val _snapshots = MutableStateFlow<WindSnapshot?>(null)
    /** Latest TrueWind-mode snapshot; null until the first tick. */
    val snapshots: StateFlow<WindSnapshot?> = _snapshots.asStateFlow()

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile var engine: WindEngine? = null
        private set
    private var engineKey: List<Any>? = null
    @Volatile var lastReport: CycleReport? = null
        private set

    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile private var forceNext = false

    // Latest inputs (written by collectors, read by the tick and fetch loops).
    @Volatile private var settings: HeadwindSettings? = null
    @Volatile private var record: ForecastRecord? = null
    @Volatile private var fieldVisible = false
    @Volatile private var appScreenOpen = false
    @Volatile private var recording = false
    @Volatile private var livePosition: GeoPoint? = null
    @Volatile private var storedPosition: GeoPoint? = null
    @Volatile private var gpsCourse: Double? = null
    @Volatile private var gpsAccuracy: Double? = null
    @Volatile private var speed: Double? = null
    @Volatile private var power: Double? = null
    @Volatile private var power3s: Double? = null
    @Volatile private var cadence: Double? = null
    @Volatile private var grade: Double? = null
    @Volatile private var karooHeading: Double? = null
    @Volatile private var elevation: Double? = null
    @Volatile private var pressureHpa: Double? = null
    @Volatile private var riderMass: Double? = null
    @Volatile private var route: UpcomingRoute? = null
    @Volatile private var bikeKey: String = DEFAULT_BIKE

    private var estimator: RiderWindEstimator? = null
    private var estimatorBike: String? = null
    private var maxWind: Double? = null
    private var rainCache: Pair<Long, Int?>? = null
    private var rainSnapshotRef: Any? = null
    private var legacyCache: Triple<Long, ForecastRecord?, de.timklge.karooheadwind.weatherprovider.WeatherData?>? = null
    private var lastBiasSaveMs = 0L
    private var lastWakePosition: GeoPoint? = null

    /** Requests a cycle on the next loop iteration regardless of the plan (settings "refresh"). */
    fun forceCycle() {
        forceNext = true
        wake.trySend(Unit)
    }

    /** Connectivity callback: resets the failure backoff and wakes the fetch loop. */
    fun onConnectivityChanged() {
        engine?.onConnectivityChanged()
        wake.trySend(Unit)
    }

    private fun <T> startWith(initial: T, f: Flow<T>): Flow<T> = flow { emit(initial); emitAll(f) }

    fun start(): Job = scope.launch {
        launch { settingsFlow.collect { settings = it; wake.trySend(Unit) } }
        launch { startWith(null, forecastRecordFlow).collect { record = it } }
        val visible = startWith(false, hub.activeRidePage.map { p -> TickPolicy.anyFieldVisible(p.page.elements.map { it.dataTypeId }) })
        val rec = startWith(false, hub.rideState.map { it is RideState.Recording })
        launch { visible.collect { fieldVisible = it; wake.trySend(Unit) } }
        launch {
            var wasRecording = false
            rec.collect {
                if (it && !wasRecording) maxWind = null // new ride (or resumed): SPEC 5 "max wind this ride" restarts on recording start
                wasRecording = it
                recording = it
                wake.trySend(Unit)
            }
        }
        launch { screenOpen.collect { appScreenOpen = it; wake.trySend(Unit) } }
        launch { bikeKeyFlow.collect { bikeKey = it } }

        combine(startWith<HeadwindSettings?>(null, settingsFlow), visible, startWith(false, screenOpen), rec) { s, v, o, r ->
            Diagnostics.activityInputs = "settings=${s != null} upstream=${s?.upstreamCompatibleMode} welcome=${s?.welcomeDialogAccepted} fieldVisible=$v screenOpen=$o recording=$r"
            TickPolicy.shouldRun(s, v, o, r)
        }.distinctUntilChanged().collectLatest { run ->
            _running.value = run
            Diagnostics.tickRunning = run
            Log.i(KarooHeadwindExtension.TAG, "TrueWind pipeline run=$run (${Diagnostics.activityInputs})")
            if (run) runActive()
        }
    }

    private suspend fun runActive() = coroutineScope {
        if (BuildConfig.DEBUG) Log.d(KarooHeadwindExtension.TAG, "TrueWind pipeline active")
        storedPosition = lastKnownPosition()?.let { GeoPoint(it.lat, it.lon) }
        // Sensor collectors exist only while active (one shared subscription each via SensorHub).
        launch {
            hub.stream(DataType.Type.LOCATION).collect { st ->
                val v = (st as? StreamState.Streaming)?.dataPoint?.values ?: return@collect
                val lat = v[DataType.Field.LOC_LATITUDE]
                val lon = v[DataType.Field.LOC_LONGITUDE]
                val acc = v[DataType.Field.LOC_ACCURACY]
                if (lat != null && lon != null && acc != null && acc < 500) {
                    livePosition = GeoPoint(lat, lon)
                    gpsAccuracy = acc
                    gpsCourse = v[DataType.Field.LOC_BEARING]
                    val last = lastWakePosition
                    if (last == null || haversineKm(last, livePosition!!) >= 1.0) {
                        lastWakePosition = livePosition
                        wake.trySend(Unit)
                    }
                }
            }
        }
        launch { hub.stream(DataType.Type.SPEED).collect { speed = it.single() } }
        launch { hub.stream(DataType.Type.POWER).collect { power = it.single() } }
        launch { hub.stream(DataType.Type.SMOOTHED_3S_AVERAGE_POWER).collect { power3s = it.single() } }
        launch { hub.stream(DataType.Type.CADENCE).collect { cadence = it.single() } }
        launch { hub.stream(DataType.Type.ELEVATION_GRADE).collect { grade = it.single() } }
        launch { hub.stream(DataType.Type.PRESSURE_ELEVATION_CORRECTION).collect { elevation = it.single() } }
        launch {
            hub.stream(DataType.Type.HEADING).collect {
                karooHeading = it.single()
                if (karooHeading != null) Diagnostics.headingEvents.incrementAndGet()
            }
        }
        launch { hub.userProfile.collect { p -> riderMass = p.weight.toDouble().takeIf { it > 0 } } }
        launch {
            hub.upcomingRoute.collect { r ->
                val changed = (r?.routePolyline) != (route?.routePolyline)
                route = r
                if (changed) wake.trySend(Unit)
            }
        }
        launch { barometer.collect { pressureHpa = it } }

        launch { fetchLoop() }
        try {
            tickLoop()
        } finally {
            withContext(NonCancellable) { saveBias(force = true) }
        }
    }

    private fun StreamState.single(): Double? = (this as? StreamState.Streaming)?.dataPoint?.singleValue

    /** Position for the tick and the fetch: debug override, live fix, then the last known position. */
    private fun currentPosition(): GeoPoint? {
        DebugOverrides.position?.let { Diagnostics.positionSource = "debug-override"; return it }
        livePosition?.let { Diagnostics.positionSource = "gps"; return it }
        storedPosition?.let { Diagnostics.positionSource = "last-known"; return it }
        Diagnostics.positionSource = "none"
        return null
    }

    private fun engineFor(s: HeadwindSettings): WindEngine {
        val key = TickPolicy.engineKey(s)
        val current = engine
        if (current != null && key == engineKey) return current
        val e = engineFactory.create(s)
        engine = e
        engineKey = key
        forceNext = forceNext || current != null
        return e
    }

    private suspend fun fetchLoop() {
        while (true) {
            val s = settings
            if (s != null) {
                val eng = engineFor(s)
                val now = clock.nowMs()
                val pos = currentPosition()
                val r = route
                val ctx = CycleContext(
                    nowMs = now,
                    rider = pos?.let { TickPolicy.roundForFetch(it, s) },
                    riderElevationM = elevation,
                    recording = recording,
                    visible = fieldVisible || appScreenOpen,
                    routeHash = r?.routePolyline?.hashCode()?.toLong(),
                    routePoints = routeForecastPoints(pos, r, s),
                    force = forceNext,
                )
                forceNext = false
                val report = eng.runCycleIfDue(ctx)
                val end = clock.nowMs()
                if (report.ran) {
                    lastReport = report
                    recordCycle(report, now, end)
                }
                // A plan that ran carries no nextCheckAtMs: the next time-based check is one interval on.
                val plannedNext = report.plan.nextCheckAtMs ?: (now + report.plan.intervalMs).takeIf { report.ran }
                Diagnostics.nextCheckAtMs = plannedNext
                val next = plannedNext ?: (end + maxFetchWaitMs)
                val waitMs = (next - end).coerceIn(tickIntervalMs, maxFetchWaitMs)
                withTimeoutOrNull(waitMs) { wake.receive() }
            } else {
                wake.receive()
            }
        }
    }

    private fun routeForecastPoints(pos: GeoPoint?, r: UpcomingRoute?, s: HeadwindSettings): List<GeoPoint> {
        if (pos == null || r == null) return emptyList()
        return try {
            upcomingRoutePoints(GpsCoordinates(pos.lat, pos.lon), r, s.getForecastMetersPerHour(false).toDouble())
                .drop(1).map { GeoPoint(it.lat, it.lon) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun recordCycle(report: CycleReport, startMs: Long, endMs: Long) {
        Diagnostics.cycles.incrementAndGet()
        if (report.success) {
            Diagnostics.cyclesSucceeded.incrementAndGet()
            Diagnostics.lastSuccessfulCycleMs = startMs
        }
        Diagnostics.memberWeights = report.weights
        Diagnostics.lastCycle = Diagnostics.CycleSummary(
            startedAtMs = startMs,
            durationMs = endMs - startMs,
            success = report.success,
            reasons = report.plan.reasons.map { it.name },
            providers = report.providers.map { Diagnostics.ProviderSummary(it.providerId, it.success, it.requests, it.bytes, it.httpStatus, it.error) },
            observationsAccepted = report.observationsAccepted,
            observationsRejected = report.observationsRejected.size,
            forecastPoints = report.forecastPoints.size,
            requests = report.providers.sumOf { it.requests },
            bytes = report.providers.sumOf { it.bytes },
        )
        val rider = report.forecastPoints.firstOrNull()
        Diagnostics.stationsInUse = engine?.latestSnapshot?.stations?.map { o ->
            Diagnostics.StationSummary(
                id = o.stationId, source = o.source,
                distanceKm = rider?.let { haversineKm(it, o.location) } ?: 0.0,
                ageMin = (startMs - o.timeMs) / 60_000.0,
                speedMs = o.speed,
            )
        }?.sortedBy { it.distanceKm } ?: emptyList()
        Log.i(KarooHeadwindExtension.TAG, "cycle ${if (report.success) "ok" else "failed"} reasons=${report.plan.reasons} " +
            report.providers.joinToString(" ") { "${it.providerId}:${if (it.success) "ok" else "err"}/${it.requests}req/${it.bytes}B${it.httpStatus?.let { s -> "/$s" } ?: ""}" } +
            " obs=${report.observationsAccepted} points=${report.forecastPoints.size} ${endMs - startMs}ms")
    }

    private suspend fun tickLoop() {
        while (true) {
            val t0 = System.nanoTime()
            tick()
            Diagnostics.recordTick(System.nanoTime() - t0)
            delay(tickIntervalMs)
        }
    }

    private suspend fun ensureEstimator(s: HeadwindSettings): RiderWindEstimator {
        val bike = bikeKey
        var est = estimator
        if (est == null || estimatorBike != bike) {
            if (est != null) saveBias(force = true)
            val (bias, variance) = loadBias(bike)
            est = RiderWindEstimator(TrueWindMapper.riderWindConfig(s, riderMass, bias, variance))
            estimator = est
            estimatorBike = bike
            return est
        }
        val wanted = TrueWindMapper.riderWindConfig(s, riderMass, est.config.virtualWind.initialBias, est.config.virtualWind.initialBiasVariance)
        if (wanted != est.config) est.updateConfig(wanted)
        return est
    }

    /** One tick: no network, reads the engine cache and the latest sensor values. */
    internal suspend fun tick() {
        val s = settings ?: return
        val now = clock.nowMs()
        val eng = engine
        val pos = currentPosition()
        val sample = if (eng != null && pos != null) eng.sample(now, pos) else FusedSample.noData(now)
        val est = ensureEstimator(s)
        val inputs = TickInputs(
            nowMs = now, position = pos, gpsCourseDeg = gpsCourse, gpsAccuracyM = gpsAccuracy, speedMps = speed,
            karooHeadingDeg = karooHeading, routeBearingDeg = route?.let { routeBearingAt(it) }, elevationM = elevation,
            sensorPressureHpa = pressureHpa, powerW = power, power3sW = power3s, cadenceRpm = cadence, gradePercent = grade,
            riderMassKg = riderMass,
        )
        val out = est.update(TrueWindMapper.riderInputs(sample, inputs, s))

        // Rain timing: recomputed when the cache changes or once a minute.
        val snapRef = eng?.latestSnapshot
        val rc = rainCache
        val rain = if (rc != null && snapRef === rainSnapshotRef && now - rc.first < 60_000L) rc.second else {
            val v = if (eng != null && pos != null && snapRef != null) {
                TrueWindMapper.rainInMinutes(now, { t -> eng.sample(t, pos).weather?.precipitationMm })
            } else WindExtras.RAIN_UNKNOWN
            rainCache = now to v
            rainSnapshotRef = snapRef
            v
        }

        // Hourly widget forecast at the rider (UV, day/night), refreshed once a minute.
        val rec = record
        val lc = legacyCache
        val legacy = if (lc != null && lc.second === rec && now - lc.first < 60_000L) lc.third else {
            val w = try {
                currentWeatherAt(rec?.response, pos?.let { GpsCoordinates(it.lat, it.lon) }, now)
            } catch (e: Exception) {
                null
            }
            legacyCache = Triple(now, rec, w)
            w
        }

        val provisional = TrueWindMapper.toSnapshot(sample, out, inputs, s, legacy, Diagnostics.lastSuccessfulCycleMs,
            Diagnostics.nextCheckAtMs, maxWind, rain)
        if (recording) {
            provisional.headline?.speed?.let { v -> maxWind = max(maxWind ?: 0.0, v) }
        }
        val snapshot = if (provisional.extras.maxWindThisRide != maxWind) provisional.copy(extras = provisional.extras.copy(maxWindThisRide = maxWind)) else provisional
        _snapshots.value = snapshot
        Diagnostics.stateEmits.incrementAndGet()
        Diagnostics.virtualSensor = Diagnostics.VirtualSensorSummary(
            active = out.virtualSensorActive, measuredHeadwind = out.measuredHeadwind, innovationMean = out.innovationMean,
            bias = out.bias, biasFrozen = out.biasFrozen, effectiveRatio = out.effectiveRatio, headingSource = out.headingSource.name,
        )
        if (now - lastBiasSaveMs >= BIAS_SAVE_INTERVAL_MS) saveBias(force = false)
    }

    private suspend fun loadBias(bike: String): Pair<Double?, Double?> {
        val raw = store.get(biasKey(bike)) ?: return null to null
        val parts = raw.split(';')
        return parts.getOrNull(0)?.toDoubleOrNull() to parts.getOrNull(1)?.toDoubleOrNull()
    }

    /** Persists the learned bias per bike, only once it has been learned (unfrozen at least once). */
    private suspend fun saveBias(force: Boolean) {
        lastBiasSaveMs = clock.nowMs()
        val est = estimator ?: return
        val bike = estimatorBike ?: return
        val sensor = est.virtualSensor
        if (sensor.measurementCount == 0 || (sensor.biasFrozen && !force)) return
        if (sensor.biasFrozen && sensor.bias == est.config.virtualWind.initialBias) return
        store.put(biasKey(bike), "${sensor.bias};${sensor.biasVariance}")
    }

    companion object {
        const val DEFAULT_BIKE = "default"
        const val BIAS_SAVE_INTERVAL_MS = 5 * 60_000L
        fun biasKey(bike: String) = "truewind.bias.$bike"
    }
}
