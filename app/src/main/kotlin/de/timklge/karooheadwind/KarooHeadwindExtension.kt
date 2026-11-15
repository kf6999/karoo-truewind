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
 */

package de.timklge.karooheadwind

import android.util.Log
import com.mapbox.geojson.LineString
import com.mapbox.turf.TurfConstants
import com.mapbox.turf.TurfMeasurement
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
import de.timklge.karooheadwind.datatypes.UviDataType
import de.timklge.karooheadwind.datatypes.WeatherForecastDataType
import de.timklge.karooheadwind.datatypes.WindAggregator
import de.timklge.karooheadwind.datatypes.WindDirectionAndSpeedDataType
import de.timklge.karooheadwind.datatypes.WindDirectionAndSpeedDataTypeCircle
import de.timklge.karooheadwind.datatypes.WindDirectionDataType
import de.timklge.karooheadwind.datatypes.WindForecastDataType
import de.timklge.karooheadwind.datatypes.WindGustsDataType
import de.timklge.karooheadwind.datatypes.WindSpeedDataType
import de.timklge.karooheadwind.fit.HeadwindFitFileWriter
import de.timklge.karooheadwind.pipeline.KarooSystemStreamSource
import de.timklge.karooheadwind.pipeline.SensorHub
import de.timklge.karooheadwind.pipeline.WindRepository
import de.timklge.karooheadwind.util.Updater
import de.timklge.karooheadwind.weatherprovider.WeatherProviderFactory
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.extension.KarooExtension
import io.hammerhead.karooext.internal.Emitter
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.absoluteValue
import kotlin.math.roundToInt
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
    private var pipelineScope: CoroutineScope? = null

    private var updateLastKnownGpsJob: Job? = null
    private var serviceJob: Job? = null
    private var windAggregationJob: Job? = null
    private var updateCheckJob: Job? = null

    override val types by lazy {
        listOf(
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
            WindDirectionAndSpeedDataType(windRepository, applicationContext),
            RelativeGradeDataType(windRepository),
            RelativeElevationGainDataType(windRepository),
            TemperatureDataType(windRepository),
            UviDataType(windRepository),
            ResistanceForcesDataType(windRepository),
            CompassDataType(windRepository, applicationContext)
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
    override fun onCreate() {
        super.onCreate()

        karooSystem = KarooSystemService(applicationContext)
        ServiceStatusSingleton.getInstance().setServiceStatus(true)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        pipelineScope = scope
        sensorHub = SensorHub(KarooSystemStreamSource(karooSystem), scope)
        windRepository = WindRepository(
            scope = scope,
            sensorHub = sensorHub,
            settingsSource = streamSettings(karooSystem),
            widgetSettingsSource = streamWidgetSettings(),
            forecastRecordSource = streamForecastRecord(),
            lastKnownPosition = { getLastKnownPosition() },
            isK2 = { karooSystem.hardwareType == HardwareType.K2 },
        )

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

            val gpsFlow = windRepository.location
                .distinctUntilChanged { old, new ->
                    if (old != null && new != null) {
                        old.distanceTo(new).absoluteValue < 0.001
                    } else {
                        old == new
                    }
                }
                .throttle(5_000L)

            var requestedGpsCoordinates: List<GpsCoordinates> = emptyList()

            val settingsStream = windRepository.settings.filter { it.welcomeDialogAccepted }

            data class StreamData(val settings: HeadwindSettings, val gps: GpsCoordinates?, val profile: UserProfile?, val upcomingRoute: UpcomingRoute?)
            data class StreamDataIdentity(val settings: HeadwindSettings, val gpsLat: Double?, val gpsLon: Double?, val profile: UserProfile?, val routePolyline: LineString?)

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
                if (BuildConfig.DEBUG) Log.d(TAG, "Acquired updated gps coordinates: $gps")

                val lastKnownStats = try {
                    streamStats().first()
                } catch(e: Exception){
                    Log.e(TAG, "Failed to read stats", e)
                    HeadwindStats()
                }

                if (gps == null){
                    error("No GPS coordinates available")
                }

                if (upcomingRoute != null){
                    val positionOnRoute = upcomingRoute.distanceAlongRoute
                    if (BuildConfig.DEBUG) Log.d(TAG, "Position on route: ${positionOnRoute}m")
                    val distancePerHour = settings.getForecastMetersPerHour(profile?.preferredUnit?.distance == UserProfile.PreferredUnit.UnitType.IMPERIAL).toDouble()
                    val msSinceFullHour = let {
                        val now = LocalDateTime.now()
                        val startOfHour = now.truncatedTo(ChronoUnit.HOURS)

                        ChronoUnit.MILLIS.between(startOfHour, now)
                    }
                    val msToNextFullHour = (1_000 * 60 * 60) - msSinceFullHour
                    val calculatedDistanceToNextFullHour = ((msToNextFullHour / (1_000.0 * 60 * 60)) * distancePerHour).coerceIn(0.0, distancePerHour)

                    if (BuildConfig.DEBUG) Log.d(TAG, "Minutes to next full hour: ${msToNextFullHour / 1000 / 60}, Distance to next full hour: ${(calculatedDistanceToNextFullHour / 1000).roundToInt()}km")

                    requestedGpsCoordinates = buildList {
                        add(GpsCoordinates(gps.lat, gps.lon, gps.bearing, distanceAlongRoute = positionOnRoute))

                        var currentPosition = positionOnRoute + calculatedDistanceToNextFullHour
                        var lastRequestedPosition = positionOnRoute

                        while (currentPosition < upcomingRoute.routeLength && size < 10) {
                            val point = TurfMeasurement.along(
                                upcomingRoute.routePolyline,
                                currentPosition,
                                TurfConstants.UNIT_METERS
                            )
                            add(
                                GpsCoordinates(
                                    point.latitude(),
                                    point.longitude(),
                                    distanceAlongRoute = currentPosition
                                )
                            )

                            lastRequestedPosition = currentPosition
                            currentPosition += distancePerHour
                        }

                        if (upcomingRoute.routeLength > lastRequestedPosition + 1_000) {
                            val point = TurfMeasurement.along(
                                upcomingRoute.routePolyline,
                                upcomingRoute.routeLength,
                                TurfConstants.UNIT_METERS
                            )
                            add(
                                GpsCoordinates(
                                    point.latitude(),
                                    point.longitude(),
                                    distanceAlongRoute = upcomingRoute.routeLength
                                )
                            )
                        }
                    }
                } else {
                    requestedGpsCoordinates = mutableListOf(gps)
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
            }.collect { response ->
                try {
                    if (BuildConfig.DEBUG) Log.d(TAG, "Got updated weather info: $response")

                    saveWidgetSettings(applicationContext, HeadwindWidgetSettings(currentForecastHourOffset = 0))
                } catch(e: Exception){
                    Log.e(TAG, "Failed to read current weather data", e)
                }
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

        pipelineScope?.cancel()
        pipelineScope = null

        karooSystem.disconnect()
        super.onDestroy()
    }
}