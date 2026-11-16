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
import de.timklge.karooheadwind.ForecastRecord
import de.timklge.karooheadwind.HeadwindSettings
import de.timklge.karooheadwind.KarooHeadwindExtension
import de.timklge.karooheadwind.datatypes.ActiveFields
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.datatypes.ParityFields
import de.timklge.karooheadwind.lerpWeatherTime
import de.timklge.karooheadwind.weatherprovider.WeatherData
import de.timklge.karooheadwind.weatherprovider.WeatherDataResponse
import io.github.kf6999.truewind.core.alerts.AlertEngine
import io.github.kf6999.truewind.core.alerts.AlertInputs
import io.github.kf6999.truewind.core.alerts.AlertText
import io.github.kf6999.truewind.core.alerts.PrecipStep
import io.github.kf6999.truewind.core.alerts.RainBarModel
import io.github.kf6999.truewind.core.alerts.RainBars
import io.github.kf6999.truewind.core.alerts.RideSummaryBuilder
import io.github.kf6999.truewind.core.alerts.nws.NwsAlert
import io.github.kf6999.truewind.core.alerts.nws.NwsAlerts
import io.github.kf6999.truewind.core.alerts.storm.StationThunder
import io.github.kf6999.truewind.core.alerts.storm.StormEstimate
import io.github.kf6999.truewind.core.alerts.storm.StormEstimator
import io.github.kf6999.truewind.core.api.ForecastStep
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.math.haversineKm
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.model.WindVector
import io.github.kf6999.truewind.core.providers.airquality.AirQuality
import io.github.kf6999.truewind.core.providers.airquality.AirQualityProvider
import io.github.kf6999.truewind.core.providers.airquality.AirQualityResult
import io.github.kf6999.truewind.core.providers.obs.NwsObservationProvider
import io.github.kf6999.truewind.core.providers.radar.PngDecoder
import io.github.kf6999.truewind.core.providers.radar.RadarFramePolicy
import io.github.kf6999.truewind.core.providers.radar.TilePlanner
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowForecastProvider
import io.github.kf6999.truewind.core.providers.wildfire.FireDistance
import io.github.kf6999.truewind.core.providers.wildfire.FirePerimeter
import io.github.kf6999.truewind.core.providers.wildfire.NifcPerimeters
import io.github.kf6999.truewind.core.route.FinishAlertMachine
import io.github.kf6999.truewind.core.route.RoutePolyline
import io.github.kf6999.truewind.core.route.SpeedModel
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.InRideAlert
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.floor

/** What the side features need right now (pure; unit-tested). */
data class ParityNeeds(
    val airQuality: Boolean,
    val nwsAlerts: Boolean,
    val fires: Boolean,
    val stormRadar: Boolean,
    val rainBars: Boolean,
    val alerts: Boolean,
) {
    companion object {
        /**
         * [active]: our type ids whose stream runs or that are on the ride page. [us]: NWS coverage
         * from the cached points lookup (null = unknown, treated as possibly US).
         */
        fun of(s: HeadwindSettings, active: Set<String>, recording: Boolean, us: Boolean?): ParityNeeds {
            val maybeUs = us != false
            val alerting = recording
            return ParityNeeds(
                airQuality = active.any { it in ParityFields.AIR_QUALITY_IDS } || (alerting && s.alertAqi),
                nwsAlerts = maybeUs && (ParityFields.STORM in active || (alerting && (s.alertNws || s.alertStorm))),
                fires = maybeUs && alerting && s.alertWildfire,
                stormRadar = ParityFields.STORM in active || (alerting && s.alertStorm),
                rainBars = ParityFields.RAIN_BAR in active || (alerting && s.alertRain),
                alerts = alerting,
            )
        }
    }
}

/**
 * WP13-16 runtime: side fetches (air quality, NWS alerts, NIFC fire perimeters, storm radar), the
 * derived states the new fields show (rain bars, storm estimate), in-ride alerts, the start-of-ride
 * summary and the finish alert.
 *
 * Runs only while the TrueWind pipeline runs (TickPolicy: a field visible, the app open, or a ride
 * recording). Side fetches use [SideFetchPolicy] so they coincide with the engine's fetch cycle;
 * every request goes through the KarooHttp [http] client and is counted in [Diagnostics].
 */
class ParityHub(
    private val scope: CoroutineScope,
    private val sensorHub: SensorHub,
    private val settingsFlow: Flow<HeadwindSettings>,
    private val pipeline: TrueWindPipeline,
    private val http: HttpClient,
    private val forecastRecord: Flow<ForecastRecord?>,
    private val dispatchAlert: (InRideAlert) -> Unit,
    decoder: PngDecoder,
    private val clock: Clock = Clock.SYSTEM,
    private val activeIds: StateFlow<Set<String>> = ActiveFields.ids,
    private val forceVisible: StateFlow<Boolean> = DebugOverrides.forceVisible,
    private val loopMs: Long = 15_000L,
    private val alertEveryMs: Long = 45_000L,
) {
    // Providers owned by the engine (set by the engine factory; shared caches).
    @Volatile var nws: NwsObservationProvider? = null
    @Volatile var tomorrow: TomorrowForecastProvider? = null

    /** Thunder-reporting METAR stations from the engine's last AWC fetch. */
    val metarThunder = MutableStateFlow<List<StationThunder>>(emptyList())

    val airQuality = MutableStateFlow<AirQuality?>(null)
    val nwsAlerts = MutableStateFlow<List<NwsAlert>>(emptyList())
    val fires = MutableStateFlow<List<FirePerimeter>>(emptyList())
    val storm = MutableStateFlow<StormEstimate?>(null)
    val stormEvaluatedAtMs = MutableStateFlow<Long?>(null)
    val rainBars = MutableStateFlow<RainBarModel?>(null)
    val radar = RadarService(http, decoder, clock)

    private val aqProvider = AirQualityProvider(http, clock)
    internal val aqPolicy = SideFetchPolicy(30 * 60_000L)
    internal val nwsPolicy = SideFetchPolicy(10 * 60_000L)
    internal val firePolicy = SideFetchPolicy(30 * 60_000L)
    internal val stormPolicy = SideFetchPolicy(10 * 60_000L)
    private val stormFrames = RadarFramePolicy<ByteArray>(clock, keepFrames = 1)
    private val stormEstimator = StormEstimator()

    private var alertEngine = AlertEngine(clock)
    private var alertEngineKey: Any? = null
    val dispatcher = AlertDispatcher(dispatchAlert)
    private var lastAlertEvalMs = 0L
    private var lastRainBarsMs = 0L
    private var lastFetchPoint: GeoPoint? = null

    @Volatile private var settings: HeadwindSettings? = null
    @Volatile private var record: ForecastRecord? = null
    @Volatile private var profile: UserProfile? = null
    @Volatile private var timeToDestinationMs: Long? = null

    // Ride bookkeeping.
    @Volatile var rideId: String? = null
        private set
    @Volatile private var summaryPendingSinceMs: Long? = null
    private var finish: FinishAlertMachine? = null
    private var finishKey: Any? = null
    @Volatile var finishState: String = "IDLE"
        private set

    @Volatile var needs: ParityNeeds? = null
        private set
    @Volatile var lastLoopMs: Long? = null
        private set

    /** Rider position as the pipeline sees it (debug override, GPS, last known). */
    fun riderPosition(): GeoPoint? = pipeline.riderPosition()
    val lastCycleEndMs: StateFlow<Long?> get() = pipeline.lastCycleEndMs

    /** Debug only: evaluate alerts while not recording (device checks). */
    val alertsWhileIdle = MutableStateFlow(false)

    fun start(): Job = scope.launch {
        launch { settingsFlow.collect { settings = it } }
        launch { forecastRecord.collect { record = it } }
        launch {
            sensorHub.userProfile.collect {
                profile = it
                de.timklge.karooheadwind.util.DisplayPrefs.imperial = it.preferredUnit.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL
            }
        }
        launch {
            var prev: RideState? = null
            sensorHub.rideState.collect { st ->
                if (st is RideState.Recording && prev is RideState.Idle) onRideStart()
                if (st is RideState.Idle) { rideId = null; summaryPendingSinceMs = null }
                prev = st
            }
        }
        pipeline.running.collectLatest { run -> if (run) runActive() }
    }

    private suspend fun runActive() = coroutineScope {
        launch {
            sensorHub.stream(DataType.Type.TIME_TO_DESTINATION).collect { st ->
                timeToDestinationMs = (st as? StreamState.Streaming)?.dataPoint?.values?.get(DataType.Field.TIME_TO_DESTINATION)?.toLong()
            }
        }
        while (true) {
            try {
                loopOnce()
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(KarooHeadwindExtension.TAG, "parity loop: ${e.javaClass.simpleName}: ${e.message}")
            }
            withTimeoutOrNull(loopMs) { pipeline.lastCycleEndMs.drop(1).first() }
        }
    }

    /** Ride start (Idle -> Recording): reset alert state, arm the 6-hour summary. */
    fun onRideStart() {
        val now = clock.nowMs()
        rideId = "ride-$now"
        alertEngine.reset()
        dispatcher.reset()
        summaryPendingSinceMs = now
        Log.i(KarooHeadwindExtension.TAG, "ride start: alerts reset, summary armed")
    }

    private fun activeSet(): Set<String> {
        val page = sensorHub.activeRidePage.replayCache.firstOrNull()?.page?.elements?.map { it.dataTypeId }.orEmpty()
        val prefix = DataType.dataTypeId(KarooHeadwindExtension.EXTENSION_ID, "")
        val onPage = page.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        val forced = if (forceVisible.value) ParityFields.ALL_IDS else emptyList()
        return activeIds.value + onPage + forced
    }

    internal suspend fun loopOnce() {
        val s = settings ?: return
        if (s.upstreamCompatibleMode) return
        val now = clock.nowMs()
        lastLoopMs = now
        val pos = pipeline.riderPosition() ?: return
        val fetchPos = TickPolicy.roundForFetch(pos, s)
        val recording = pipeline.isRecording || alertsWhileIdle.value
        val n = ParityNeeds.of(s, activeSet(), recording, nws?.cachedCoverage(fetchPos))
        needs = n
        val cycleEnd = pipeline.lastCycleEndMs.value

        // Moving far invalidates position-bound caches (fires, alerts, AQ).
        lastFetchPoint?.let { if (haversineKm(it, fetchPos) > 20.0) { aqPolicy.reset(); nwsPolicy.reset(); firePolicy.reset() } }

        // --- side fetches, back to back (one radio burst) ---
        if (aqPolicy.decide(now, n.airQuality, cycleEnd) == SideFetchPolicy.Decision.FETCH) {
            val r = aqProvider.fetch(fetchPos, force = true)
            if (r is AirQualityResult.Success) airQuality.value = r.airQuality
            aqPolicy.onResult(clock.nowMs(), r is AirQualityResult.Success)
            lastFetchPoint = fetchPos
        }
        if (nwsPolicy.decide(now, n.nwsAlerts, cycleEnd) == SideFetchPolicy.Decision.FETCH) {
            val r = NwsAlerts.fetch(http, fetchPos)
            if (r.error == null) nwsAlerts.value = r.items
            nwsPolicy.onResult(clock.nowMs(), r.error == null)
            lastFetchPoint = fetchPos
        }
        if (firePolicy.decide(now, n.fires, cycleEnd) == SideFetchPolicy.Decision.FETCH) {
            val r = NifcPerimeters.fetch(http, fetchPos, radiusKm = 30.0)
            if (r.error == null) fires.value = r.items
            firePolicy.onResult(clock.nowMs(), r.error == null)
            lastFetchPoint = fetchPos
        }
        // Storm radar: reuse the radar field's grid when it computed one recently.
        val core = radar.latestCore.value
        val coreFresh = core != null && now - core.third < stormPolicy.intervalMs
        if (!coreFresh && stormPolicy.decide(now, n.stormRadar, cycleEnd) == SideFetchPolicy.Decision.FETCH) {
            val plan = TilePlanner.plan(fetchPos, STORM_ZOOM, STORM_VIEW_PX, STORM_VIEW_PX)
            val r = radar.refresh(plan, stormFrames, computeCoreAt = pos)
            stormPolicy.onResult(clock.nowMs(), r.ok)
        }

        // --- derived states ---
        if (n.stormRadar || n.nwsAlerts) {
            val c = radar.latestCore.value?.second
            storm.value = stormEstimator.estimate(pos, now, radarCore = c, stations = metarThunder.value, nwsAlerts = nwsAlerts.value)
            stormEvaluatedAtMs.value = now
        }
        if (n.rainBars && now - lastRainBarsMs >= 60_000L) {
            rainBars.value = buildRainBars(now, pos)
            lastRainBarsMs = now
        }

        // --- alerts (slow path, recording only) ---
        if (n.alerts && now - lastAlertEvalMs >= alertEveryMs) {
            lastAlertEvalMs = now
            evaluateAlerts(s, now, pos)
        }
        if (pipeline.isRecording) runFinishAlert(s, now, pos)
        Diagnostics.parity = diagnostics()
    }

    private fun imperial() = profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL

    private fun evaluateAlerts(s: HeadwindSettings, now: Long, pos: GeoPoint) {
        val key = listOf(s.alertGustKmh, s.alertStormKm, s.alertWildfireKm, s.alertAqiCategory, imperial())
        if (key != alertEngineKey) {
            alertEngine = AlertEngine(clock, AlertDispatcher.alertConfig(s), AlertDispatcher.units(imperial()))
            alertEngineKey = key
        }
        // Start-of-ride summary once the forecast is there (or after 2 minutes with whatever there is).
        summaryPendingSinceMs?.let { since ->
            val series = hourlySeries(pos)
            if (series.isNotEmpty() || now - since > 120_000L) {
                summaryPendingSinceMs = null
                if (s.alertRideSummary && series.isNotEmpty()) {
                    val summary = rideSummary(s, now, pos, series)
                    dispatcher.dispatch(listOf(RideSummaryBuilder.toAlert(summary, now)), s)
                }
            }
        }
        val inputs = AlertInputs(
            precip = precipSteps(now, pos),
            currentGustMs = pipeline.snapshots.value?.headlineGust,
            windSeries = windSeries(now, pos),
            nwsAlerts = nwsAlerts.value,
            storm = storm.value,
            fires = fires.value.mapNotNull { FireDistance.proximity(pos, it) },
            usAqi = airQuality.value?.usAqi,
        )
        val sent = dispatcher.dispatch(alertEngine.evaluate(inputs), s)
        if (sent.isNotEmpty()) Log.i(KarooHeadwindExtension.TAG, "alerts dispatched: ${sent.map { it.rule }}")
    }

    private suspend fun runFinishAlert(s: HeadwindSettings, now: Long, pos: GeoPoint) {
        val cfg = AlertDispatcher.finishConfig(s)
        if (cfg != finishKey) { finish = FinishAlertMachine(cfg); finishKey = cfg }
        val m = finish ?: return
        val ttd = timeToDestinationMs?.takeIf { pipeline.upcomingRoute != null && it > 0 }
        val req = m.onUpdate(now, rideId ?: "ride", ttd, pos)
        if (req != null) {
            var ok = false
            try {
                ok = http.request(req).isSuccessful
            } catch (e: Exception) {
                if (e is kotlin.coroutines.cancellation.CancellationException) throw e
            } finally {
                // Always report, even on cancellation: otherwise the machine stays in SENDING and never retries.
                m.onSendResult(ok, clock.nowMs())
            }
            Log.i(KarooHeadwindExtension.TAG, "finish alert sent ok=$ok")
        }
        finishState = m.state.name
    }

    // ------------------------------------------------------------------ inputs from the caches

    /** 15-min precipitation steps from the fused nowcast at the rider (each covers the preceding 15 min). */
    fun precipSteps15(now: Long, pos: GeoPoint): List<PrecipStep> {
        val eng = pipeline.engine ?: return emptyList()
        val first = floor(now / STEP_MS.toDouble()).toLong() * STEP_MS
        val out = ArrayList<PrecipStep>()
        var t = first
        while (t <= now + 135 * 60_000L) {
            val smp = eng.sample(t, pos)
            if (smp.beyondHorizon) break
            smp.weather?.precipitationMm?.let { out += PrecipStep(t - STEP_MS, 15, it) }
            t += STEP_MS
        }
        return out
    }

    /** Tomorrow.io 1-minute steps (keyed), from the provider's last nowcast. */
    fun precipSteps1(): List<PrecipStep> =
        tomorrow?.lastNowcast?.precipitation?.map { PrecipStep(it.timeMs, 1, it.intensityMmPerHour / 60.0) }.orEmpty()

    private fun precipSteps(now: Long, pos: GeoPoint) = io.github.kf6999.truewind.core.alerts.mergePrecip(precipSteps15(now, pos), precipSteps1())

    fun buildRainBars(now: Long, pos: GeoPoint): RainBarModel = RainBars.build(now, precipSteps15(now, pos), precipSteps1(), horizonMin = 120)

    private fun windSeries(now: Long, pos: GeoPoint): List<ForecastStep> {
        val eng = pipeline.engine ?: return emptyList()
        val first = floor(now / STEP_MS.toDouble()).toLong() * STEP_MS
        return (0..4).mapNotNull { i ->
            val t = first + i * STEP_MS
            val smp = eng.sample(t, pos)
            if (smp.beyondHorizon && i > 0) null else ForecastStep(t, smp.model10m, smp.gust10m)
        }
    }

    /** Hourly 12 h series at [pos] from the widget forecast record. */
    fun hourlySeries(pos: GeoPoint): List<ForecastStep> {
        val forLoc = weatherForLocation(record?.response, GpsCoordinates(pos.lat, pos.lon)) ?: return emptyList()
        return forLoc.forecasts.orEmpty().map { it.toForecastStep() }
    }

    private fun rideSummary(s: HeadwindSettings, now: Long, pos: GeoPoint, series: List<ForecastStep>) =
        RideSummaryBuilder.build(
            nowMs = now, series = series, units = AlertDispatcher.units(imperial()), windowH = 6,
            route = pipeline.upcomingRoute?.let { routePolyline(it.routePolyline) },
            speed = SpeedModel.constant(s.forecastedKmPerHour.toDouble().coerceAtLeast(5.0)),
            forecast = { p, t -> forecastAt(record?.response, p, t) },
            precipProbabilityPct = weatherForLocation(record?.response, GpsCoordinates(pos.lat, pos.lon))?.forecasts.orEmpty()
                .mapNotNull { w -> w.precipitationProbability?.let { w.time * 1000 to it } }.toMap(),
            routeSampleMin = 60,
        )

    private fun diagnostics(): Map<String, Any?> = linkedMapOf(
        "needs" to needs?.toString(),
        "aqAgeMin" to aqPolicy.lastSuccessMs?.let { (clock.nowMs() - it) / 60_000 },
        "usAqi" to airQuality.value?.usAqi,
        "nwsAlerts" to nwsAlerts.value.size,
        "nwsAgeMin" to nwsPolicy.lastSuccessMs?.let { (clock.nowMs() - it) / 60_000 },
        "fires" to fires.value.size,
        "metarThunder" to metarThunder.value.size,
        "storm" to storm.value?.label(),
        "stormBasis" to storm.value?.basis?.map { it.name },
        "radarRefreshes" to radar.refreshes.get(),
        "radarRequests" to radar.requests.get(),
        "radarBytes" to radar.bytes.get(),
        "radarBudgetSkips" to radar.budgetSkips.get(),
        "radarFrameAgeMin" to radar.latestFrameTimeMs?.let { (clock.nowMs() - it) / 60_000 },
        "rainBars" to rainBars.value?.let { "${it.bars.size} bars, ${it.resolutionLabel}, first rain ${it.firstRainInMin}" },
        "alertsDispatched" to dispatcher.dispatchedCount,
        "lastAlert" to dispatcher.lastAlert?.title,
        "finishState" to finishState,
        "rideId" to rideId,
    )

    companion object {
        const val STEP_MS = 15 * 60_000L
        const val STORM_ZOOM = 7
        const val STORM_VIEW_PX = 160

        fun WeatherData.toForecastStep() = ForecastStep(
            timeMs = time * 1000,
            wind10m = WindVector.fromSpeedDirection(windSpeed, windDirection),
            gust10m = windGusts,
            temperatureC = temperature,
            relativeHumidityPct = relativeHumidity.toDouble(),
            precipitationMm = precipitation,
            cloudCoverPct = cloudCover,
            weatherCode = weatherCode,
        )

        /** Hourly widget forecast at [p] and time [t] (space- and time-interpolated); null without data. */
        fun forecastAt(response: WeatherDataResponse?, p: GeoPoint, t: Long): ForecastStep? {
            val forLoc = weatherForLocation(response, GpsCoordinates(p.lat, p.lon)) ?: return null
            return lerpWeatherTime(forLoc.forecasts, forLoc.current, t).toForecastStep().copy(timeMs = t)
        }

        /** Route polyline from the Karoo's LineString, thinned to at most [maxPoints]. */
        fun routePolyline(line: com.mapbox.geojson.LineString, maxPoints: Int = 2000): RoutePolyline? {
            val coords = line.coordinates()
            if (coords.isEmpty()) return null
            val step = maxOf(1, coords.size / maxPoints)
            val pts = coords.filterIndexed { i, _ -> i % step == 0 || i == coords.size - 1 }.map { GeoPoint(it.latitude(), it.longitude()) }
            return RoutePolyline(pts)
        }

        init {
            AlertText.zone = java.time.ZoneId.systemDefault()
        }
    }
}

/** Process-wide handle for the UI and the debug receiver (same process as the extension). */
object ParityRuntime {
    @Volatile var hub: ParityHub? = null
}
