/*
 * Copyright 2024-2026 karoo-headwind contributors
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
 *
 * Modified by TrueWind: WindEngine wiring (fetch loop + 1 Hz tick via TrueWindPipeline), KarooHttp,
 * device barometer, connectivity callback, new data types; upstream's hourly fetch loop kept for
 * the upstream-compatible mode.
 */

package de.timklge.karooheadwind

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.ConnectivityManager
import android.net.Network
import android.util.Log
import de.timklge.karooheadwind.datatypes.CloudCoverDataType
import de.timklge.karooheadwind.datatypes.CompassDataType
import de.timklge.karooheadwind.datatypes.GpsCoordinates
import de.timklge.karooheadwind.datatypes.HeadwindDirectionDataType
import de.timklge.karooheadwind.datatypes.HeadwindForecastDataType
import de.timklge.karooheadwind.datatypes.HeadwindSpeedDataType
import de.timklge.karooheadwind.datatypes.HeadwindTimeDataType
import de.timklge.karooheadwind.datatypes.HeadwindTimeDistributionDataType
import de.timklge.karooheadwind.datatypes.PrecipitationDataType
import de.timklge.karooheadwind.datatypes.PrecipitationForecastDataType
import de.timklge.karooheadwind.datatypes.RelativeElevationGainDataType
import de.timklge.karooheadwind.datatypes.RelativeGradeDataType
import de.timklge.karooheadwind.datatypes.RelativeHumidityDataType
import de.timklge.karooheadwind.datatypes.ResistanceForcesDataType
import de.timklge.karooheadwind.datatypes.SealevelPressureDataType
import de.timklge.karooheadwind.datatypes.SurfacePressureDataType
import de.timklge.karooheadwind.datatypes.TailwindAndRideSpeedDataType
import de.timklge.karooheadwind.datatypes.TemperatureDataType
import de.timklge.karooheadwind.datatypes.TemperatureForecastDataType
import de.timklge.karooheadwind.datatypes.TrueWindFields
import de.timklge.karooheadwind.datatypes.UviDataType
import de.timklge.karooheadwind.datatypes.WeatherForecastDataType
import de.timklge.karooheadwind.datatypes.WindAggregator
import de.timklge.karooheadwind.datatypes.WindDirectionAndSpeedDataType
import de.timklge.karooheadwind.datatypes.WindDirectionAndSpeedDataTypeCircle
import de.timklge.karooheadwind.datatypes.WindDirectionDataType
import de.timklge.karooheadwind.datatypes.WindForecastDataType
import de.timklge.karooheadwind.datatypes.WindGustsDataType
import de.timklge.karooheadwind.datatypes.WindSourceDataType
import de.timklge.karooheadwind.datatypes.WindSpeedDataType
import de.timklge.karooheadwind.fit.HeadwindFitFileWriter
import de.timklge.karooheadwind.pipeline.DataStoreKeyValueStore
import de.timklge.karooheadwind.pipeline.Diagnostics
import de.timklge.karooheadwind.pipeline.EngineFactory
import de.timklge.karooheadwind.pipeline.KarooHttp
import de.timklge.karooheadwind.pipeline.KarooSystemHttpTransport
import de.timklge.karooheadwind.pipeline.KarooSystemStreamSource
import de.timklge.karooheadwind.pipeline.SensorHub
import de.timklge.karooheadwind.pipeline.TrueWindPipeline
import de.timklge.karooheadwind.pipeline.TrueWindRuntime
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.pipeline.engineDataStore
import de.timklge.karooheadwind.pipeline.upcomingRoutePoints
import de.timklge.karooheadwind.util.Updater
import de.timklge.karooheadwind.weatherprovider.WeatherProviderFactory
import io.github.kf6999.truewind.core.api.HttpClient
import io.github.kf6999.truewind.core.engine.ForecastSource
import io.github.kf6999.truewind.core.engine.HourlySource
import io.github.kf6999.truewind.core.engine.ObservationSource
import io.github.kf6999.truewind.core.engine.SourceStatus
import io.github.kf6999.truewind.core.engine.WindEngine
import io.github.kf6999.truewind.core.engine.WindEngineConfig
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.AwcMetarProvider
import io.github.kf6999.truewind.core.providers.obs.NwsObservationProvider
import io.github.kf6999.truewind.core.providers.obs.SynopticProvider
import io.github.kf6999.truewind.core.providers.openmeteo.OpenMeteoForecastProvider
import io.github.kf6999.truewind.core.providers.tomorrow.TomorrowForecastProvider
import io.github.kf6999.truewind.core.schedule.FetchPlannerConfig
import io.github.kf6999.truewind.core.time.Clock
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.models.Bikes
import io.hammerhead.karooext.models.FitEffect
import io.hammerhead.karooext.models.HardwareType
import io.hammerhead.karooext.models.SystemNotification
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class KarooHeadwindExtension : KarooExtension(EXTENSION_ID, BuildConfig.VERSION_NAME) {
    companion object {
        const val EXTENSION_ID = "truewind"
        const val TAG = "truewind"
    }

    private lateinit var karooSystem: KarooSystemService
    private lateinit var windAggregator: WindAggregator
    private lateinit var sensorHub: SensorHub
    private lateinit var windRepository: WindRepository
    private lateinit var trueWind: TrueWindPipeline
    private lateinit var http: HttpClient
    private var pipelineScope: CoroutineScope? = null
    private var parityHub: de.timklge.karooheadwind.pipeline.ParityHub? = null
    /** One NWS provider across engine rebuilds (keeps the cached station list). */
    private var nwsProvider: Pair<Boolean, NwsObservationProvider>? = null
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null

    private var updateLastKnownGpsJob: Job? = null
    private var serviceJob: Job? = null
    private var windAggregationJob: Job? = null
    private var updateCheckJob: Job? = null

    override val types by lazy {
        // Wrapped so the tick policy knows when the Karoo consumes a field (ActiveFields).
        (listOf(
            HeadwindDirectionDataType(windRepository, applicationContext),
            TailwindAndRideSpeedDataType(windRepository, applicationContext),
            WindDirectionAndSpeedDataTypeCircle(windRepository, applicationContext),
            WeatherForecastDataType(windRepository),
            HeadwindSpeedDataType(windRepository),
            HeadwindTimeDataType(windRepository, windAggregator),
            HeadwindTimeDistributionDataType(windRepository, windAggregator),
            RelativeHumidityDataType(windRepository),
            CloudCoverDataType(windRepository),
            WindGustsDataType(windRepository),
            WindSpeedDataType(windRepository),
            WindDirectionDataType(windRepository),
            WindDirectionAndSpeedDataType(windRepository, applicationContext),
            PrecipitationDataType(windRepository),
            SurfacePressureDataType(windRepository),
            SealevelPressureDataType(windRepository),
            TemperatureForecastDataType(windRepository),
            PrecipitationForecastDataType(windRepository),
            WindForecastDataType(windRepository),
            HeadwindForecastDataType(windRepository),
            RelativeGradeDataType(windRepository),
            RelativeElevationGainDataType(windRepository),
            TemperatureDataType(windRepository),
            UviDataType(windRepository),
            ResistanceForcesDataType(windRepository),
            CompassDataType(windRepository, applicationContext),
            WindSourceDataType(windRepository),
        ) + TrueWindFields.numeric(windRepository)
          + de.timklge.karooheadwind.datatypes.ParityFields.all(windRepository) { de.timklge.karooheadwind.pipeline.ParityRuntime.hub }).map { de.timklge.karooheadwind.datatypes.ActiveFields.track(it) }
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()

        karooSystem = KarooSystemService(applicationContext)
        io.github.kf6999.truewind.core.alerts.AlertText.twelveHour = !android.text.format.DateFormat.is24HourFormat(applicationContext)
        ServiceStatusSingleton.getInstance().setServiceStatus(true)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pipelineScope = scope
        http = KarooHttp(KarooSystemHttpTransport(karooSystem))
        sensorHub = SensorHub(KarooSystemStreamSource(karooSystem), scope)
        val settingsFlow = streamSettings(karooSystem)
        trueWind = TrueWindPipeline(
            scope = scope,
            hub = sensorHub,
            settingsFlow = settingsFlow,
            forecastRecordFlow = streamForecastRecord(),
            lastKnownPosition = { getLastKnownPosition() },
            engineFactory = EngineFactory { s -> buildEngine(s) },
            store = DataStoreKeyValueStore(applicationContext.engineDataStore),
            bikeKeyFlow = bikeKeyFlow(),
            barometer = barometerFlow(applicationContext),
        )
        TrueWindRuntime.pipeline = trueWind
        windRepository = WindRepository(
            scope = scope,
            sensorHub = sensorHub,
            settingsSource = settingsFlow,
            widgetSettingsSource = streamWidgetSettings(),
            forecastRecordSource = streamForecastRecord(),
            lastKnownPosition = { getLastKnownPosition() },
            isK2 = { karooSystem.hardwareType == HardwareType.K2 },
            trueWind = trueWind.snapshots,
        )
        TrueWindRuntime.repository = windRepository
        parityHub =de.timklge.karooheadwind.pipeline.ParityHub(
            scope = scope,
            sensorHub = sensorHub,
            settingsFlow = settingsFlow,
            pipeline = trueWind,
            http = http,
            forecastRecord = streamForecastRecord(),
            dispatchAlert = { alert -> karooSystem.dispatch(alert) },
            decoder = de.timklge.karooheadwind.datatypes.BitmapPngDecoder,
        ).also {
            de.timklge.karooheadwind.pipeline.ParityRuntime.hub = it
            it.start()
        }
        trueWind.start()
        registerConnectivityCallback()

        updateLastKnownGpsJob = CoroutineScope(Dispatchers.IO).launch {
            updateLastKnownGps(this@KarooHeadwindExtension, windRepository.location)
        }

        windAggregator = WindAggregator()
        windAggregationJob = windAggregator.start(windRepository)

        serviceJob = CoroutineScope(Dispatchers.IO).launch {
            karooSystem.connect { connected ->
                if (connected) {
                    Log.d(TAG, "Connected to Karoo system")
                    checkForUpdates()
                }
            }

            // Upstream-compatible mode keeps karoo-headwind's hourly fetch loop; TrueWind mode
            // fetches through the WindEngine (TrueWindPipeline), which also schedules the hourly
            // widget forecast.
            windRepository.settings.map { it.upstreamCompatibleMode }.distinctUntilChanged().collectLatest { upstream ->
                if (upstream) runLegacyFetchLoop()
            }
        }
    }

    /** The engine for the current settings: Open-Meteo + AWC + NWS always; Synoptic / Tomorrow.io when keyed. */
    private fun buildEngine(s: HeadwindSettings): WindEngine {
        val clock = Clock.SYSTEM
        val openMeteo = OpenMeteoForecastProvider(http, clock)
        val omSource = ForecastSource.openMeteo(openMeteo)
        val forecast = buildList {
            add(
                if (s.layerEnsemble) omSource
                else ForecastSource(openMeteo, optional = false) { req -> omSource.fetch(req.copy(members = listOf("open-meteo:best_match"))) }
            )
            if (s.tomorrowApiKey.isNotBlank()) {
                val tomorrow = TomorrowForecastProvider(http, s.tomorrowApiKey.trim(), clock)
                parityHub?.tomorrow = tomorrow
                add(ForecastSource.tomorrow(tomorrow))
            } else parityHub?.tomorrow = null
        }
        val observations = buildList {
            // AWC as ObservationSource.awc, also keeping thunder reports for the storm estimate.
            val awc = AwcMetarProvider(http)
            add(ObservationSource(awc.id) { c, r ->
                val res = awc.fetchMetars(c, r)
                if (res.error == null) parityHub?.metarThunder?.value = io.github.kf6999.truewind.core.alerts.storm.StationThunder.fromMetars(res.items)
                io.github.kf6999.truewind.core.providers.obs.ObsFetchResult(
                    res.items.map { it.observation }.filter { it.speed != null },
                    res.requests, res.bytesReceived, res.error, res.httpStatus, res.retryAfterSec,
                )
            })
            // NWS trimmed when Synoptic covers the area: one station, two attempts.
            val synoptic = s.synopticToken.isNotBlank()
            val nws = nwsProvider?.takeIf { it.first == synoptic }?.second
                ?: (if (synoptic) NwsObservationProvider(http, clock, maxStations = 1, maxAttempts = 2, stationListLimit = 10)
                    else NwsObservationProvider(http, clock, stationListLimit = 10)).also { nwsProvider = synoptic to it }
            parityHub?.nws = nws
            add(ObservationSource.nws(nws))
            if (s.synopticToken.isNotBlank()) add(ObservationSource.synoptic(SynopticProvider(http, s.synopticToken.trim())))
        }
        val hourly = HourlySource("hourly") { points -> fetchHourlyWidgets(points) }
        val config = WindEngineConfig(
            planner = FetchPlannerConfig.forRefreshMinutes(s.refreshIntervalMin),
            useObservations = s.layerObservations,
            memberAllowed = { id ->
                if (id.startsWith("open-meteo:")) s.layerEnsemble || id == "open-meteo:best_match"
                else s.tomorrowApiKey.isNotBlank()
            },
        )
        if (BuildConfig.DEBUG) Log.d(TAG, "Engine: forecast=${forecast.map { it.id }} obs=${observations.map { it.id }} settings=$s")
        return WindEngine(forecast, observations, hourly, DataStoreKeyValueStore(applicationContext.engineDataStore), config)
    }

    /** Hourly 12 h widget forecast (route-aware, upstream provider choice), saved like upstream did. */
    private suspend fun fetchHourlyWidgets(points: List<GeoPoint>): SourceStatus {
        val rider = points.firstOrNull() ?: return SourceStatus(error = "no position")
        val settings = windRepository.settings.first()
        val profile = sensorHub.userProfile.replayCache.firstOrNull()
        val gps = GpsCoordinates(rider.lat, rider.lon)
        val route = sensorHub.upcomingRoute.replayCache.firstOrNull()
        val coordinates = if (route != null) {
            upcomingRoutePoints(gps, route, settings.getForecastMetersPerHour(profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL).toDouble())
        } else listOf(gps)
        val before = Diagnostics.httpBytes.get()
        val stats = try { streamStats().first() } catch (e: Exception) { HeadwindStats() }
        return try {
            val response = WeatherProviderFactory.makeWeatherRequest(karooSystem, coordinates, settings, profile)
            saveForecastRecord(this, response, stats.copy(
                lastSuccessfulWeatherRequest = System.currentTimeMillis(),
                lastSuccessfulWeatherPosition = gps,
                lastSuccessfulWeatherProvider = response.provider,
                lastWeatherError = null,
            ))
            saveWidgetSettings(applicationContext, HeadwindWidgetSettings(currentForecastHourOffset = 0))
            SourceStatus(bytes = Diagnostics.httpBytes.get() - before)
        } catch (e: Exception) {
            try {
                saveStats(this, stats.copy(failedWeatherRequest = System.currentTimeMillis(), lastWeatherError = e.message ?: e.toString()))
            } catch (_: Exception) {}
            SourceStatus(bytes = Diagnostics.httpBytes.get() - before, error = e.message ?: e.javaClass.simpleName)
        }
    }

    /** Bike key for the persisted virtual-sensor bias: the only bike, or the first listed one. */
    private fun bikeKeyFlow(): Flow<String> = callbackFlow {
        val id = karooSystem.addConsumer { bikes: Bikes ->
            trySend(bikes.bikes.firstOrNull()?.id ?: TrueWindPipeline.DEFAULT_BIKE)
        }
        awaitClose { karooSystem.removeConsumer(id) }
    }.distinctUntilChanged()

    private fun registerConnectivityCallback() {
        try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    trueWind.onConnectivityChanged()
                }
            }
            cm.registerDefaultNetworkCallback(cb)
            connectivityCallback = cb
        } catch (e: Exception) {
            Log.w(TAG, "Connectivity callback unavailable", e)
        }
    }

    /** karoo-headwind's fetch loop (upstream-compatible mode only). */
    private suspend fun runLegacyFetchLoop() {
        val gpsFlow = windRepository.location
            .distinctUntilChanged { old, new ->
                if (old != null && new != null) {
                    old.distanceTo(new).absoluteValue < 0.001
                } else {
                    old == new
                }
            }
            .throttle(5_000L)

        val settingsStream = windRepository.settings.filter { it.welcomeDialogAccepted }

        data class StreamData(val settings: HeadwindSettings, val gps: GpsCoordinates?, val profile: UserProfile?, val upcomingRoute: UpcomingRoute?)
        data class StreamDataIdentity(val settings: HeadwindSettings, val gpsLat: Double?, val gpsLon: Double?, val profile: UserProfile?, val routePolyline: com.mapbox.geojson.LineString?)

        combine(settingsStream, gpsFlow, sensorHub.userProfile, sensorHub.upcomingRoute) { settings, gps, profile, upcomingRoute ->
            StreamData(settings, gps, profile, upcomingRoute)
        }
        .distinctUntilChangedBy { StreamDataIdentity(it.settings, it.gps?.lat, it.gps?.lon, it.profile, it.upcomingRoute?.routePolyline) }
        .transformLatest { value ->
            while(true){
                emit(value)
                delay(1.hours)
            }
        }
        .map { (settings: HeadwindSettings, gps, profile, upcomingRoute) ->
            val lastKnownStats = try {
                streamStats().first()
            } catch(e: Exception){
                Log.e(TAG, "Failed to read stats", e)
                HeadwindStats()
            }

            if (gps == null){
                error("No GPS coordinates available")
            }

            val requestedGpsCoordinates = if (upcomingRoute != null) {
                val distancePerHour = settings.getForecastMetersPerHour(profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL).toDouble()
                upcomingRoutePoints(gps, upcomingRoute, distancePerHour)
            } else {
                listOf(gps)
            }

            val response = try {
                WeatherProviderFactory.makeWeatherRequest(karooSystem, requestedGpsCoordinates, settings, profile)
            } catch(e: Throwable){
                try {
                    saveStats(this@KarooHeadwindExtension, lastKnownStats.copy(
                        failedWeatherRequest = System.currentTimeMillis(),
                        lastWeatherError = e.message ?: e.toString()
                    ))
                } catch(writeError: Exception){
                    Log.e(TAG, "Failed to write stats", writeError)
                }
                throw e
            }

            try {
                val stats = lastKnownStats.copy(
                    lastSuccessfulWeatherRequest = System.currentTimeMillis(),
                    lastSuccessfulWeatherPosition = gps,
                    lastSuccessfulWeatherProvider = response.provider,
                    lastWeatherError = null
                )
                saveForecastRecord(this@KarooHeadwindExtension, response, stats)
            } catch(e: Exception){
                Log.e(TAG, "Failed to write forecast", e)
            }

            response
        }.retry(Long.MAX_VALUE) { e ->
            Log.w(TAG, "Failed to get weather data", e)
            delay(2.minutes); true
        }.collect { _ ->
            try {
                saveWidgetSettings(applicationContext, HeadwindWidgetSettings(currentForecastHourOffset = 0))
            } catch(e: Exception){
                Log.e(TAG, "Failed to read current weather data", e)
            }
        }
    }

    override fun startFit(emitter: Emitter<FitEffect>) {
        val fitWriteJob = HeadwindFitFileWriter(windRepository, windAggregator).start(emitter)

        emitter.setCancellable {
            fitWriteJob.cancel()
        }
    }

    private fun checkForUpdates() {
        if (updateCheckJob != null) return

        updateCheckJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                val settings = windRepository.settings.first()
                if (!settings.enableUpdateNotifications) {
                    Log.d(TAG, "Update notifications disabled, skipping update check")
                    return@launch
                }

                val update = Updater.checkForUpdate(this@KarooHeadwindExtension, karooSystem)
                if (update != null) {
                    karooSystem.dispatch(SystemNotification(
                        id = "truewind-update",
                        message = "TrueWind ${update.latestVersion} available",
                        subText = "You are running version ${update.currentVersion}. Open TrueWind for details.",
                        header = "TrueWind update",
                        style = SystemNotification.Style.UPDATE,
                        action = "Open",
                        actionIntent = "de.timklge.karooheadwind.MainActivity",
                    ))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Update check failed", e)
            }
        }
    }

    override fun onDestroy() {
        serviceJob?.cancel()
        serviceJob = null

        updateCheckJob?.cancel()
        updateCheckJob = null

        updateLastKnownGpsJob?.cancel()
        updateLastKnownGpsJob = null

        windAggregationJob?.cancel()
        windAggregationJob = null

        connectivityCallback?.let {
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it)
            } catch (_: Exception) {}
        }
        connectivityCallback = null

        TrueWindRuntime.pipeline = null
        TrueWindRuntime.repository = null
        pipelineScope?.cancel()
        pipelineScope = null

        karooSystem.disconnect()
        super.onDestroy()
    }
}

/**
 * Device barometer (`SensorManager.TYPE_PRESSURE`), hPa, for air density (SPEC 4.4). Emits null
 * when the device has no pressure sensor. Registered only while collected (the tick is running).
 * Events are counted in [Diagnostics.barometerEvents] for the on-device probe (SPEC 8.2).
 */
fun barometerFlow(context: Context): Flow<Double?> = callbackFlow {
    val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    val sensor = sm?.getDefaultSensor(Sensor.TYPE_PRESSURE)
    Diagnostics.barometerAvailable = sensor != null
    if (sm == null || sensor == null) {
        trySend(null)
        awaitClose { }
        return@callbackFlow
    }
    var lastSentMs = 0L
    val listener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val hpa = event.values.firstOrNull()?.toDouble() ?: return
            Diagnostics.barometerEvents.incrementAndGet()
            Diagnostics.lastBarometerHpa = hpa
            val now = System.currentTimeMillis()
            if (now - lastSentMs >= 5_000L) { // air density needs no more than one value per 5 s
                lastSentMs = now
                trySend(hpa)
            }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }
    sm.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
    awaitClose { sm.unregisterListener(listener) }
}
