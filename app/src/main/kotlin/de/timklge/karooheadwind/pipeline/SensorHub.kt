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

import de.timklge.karooheadwind.BuildConfig
import de.timklge.karooheadwind.UpcomingRoute
import de.timklge.karooheadwind.streamActiveRidePage
import de.timklge.karooheadwind.streamDataFlow
import de.timklge.karooheadwind.streamNavigationState
import de.timklge.karooheadwind.streamRideState
import de.timklge.karooheadwind.streamUserProfile
import de.timklge.karooheadwind.upcomingRouteFlow
import io.hammerhead.karooext.KarooSystemService
import io.hammerhead.karooext.models.ActiveRidePage
import io.hammerhead.karooext.models.DataType
import io.hammerhead.karooext.models.OnNavigationState
import io.hammerhead.karooext.models.RideState
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.UserProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import java.util.concurrent.ConcurrentHashMap

/** The raw Karoo event sources. Abstracted so the pipeline can run against fakes in JVM tests. */
interface KarooStreamSource {
    fun dataStream(dataTypeId: String): Flow<StreamState>
    fun userProfile(): Flow<UserProfile>
    fun rideState(): Flow<RideState>
    fun navigationState(): Flow<OnNavigationState>
    fun activeRidePage(): Flow<ActiveRidePage>
}

class KarooSystemStreamSource(private val karooSystem: KarooSystemService) : KarooStreamSource {
    override fun dataStream(dataTypeId: String) = karooSystem.streamDataFlow(dataTypeId)
    override fun userProfile() = karooSystem.streamUserProfile()
    override fun rideState() = karooSystem.streamRideState()
    override fun navigationState() = karooSystem.streamNavigationState()
    override fun activeRidePage() = karooSystem.streamActiveRidePage()
}

/**
 * Exactly one subscription per Karoo stream, shared by every consumer in the process
 * (SPEC section 3, G8). Each stream is subscribed while someone collects it and released
 * [STOP_TIMEOUT_MS] after the last collector leaves; the replay cache is cleared on release so a
 * later subscriber never sees a stale value first.
 */
class SensorHub(source: KarooStreamSource, private val scope: CoroutineScope) {
    // TrueWind debug builds: replay mode may substitute recorded streams (TESTING 4, T3).
    private val source: KarooStreamSource = if (BuildConfig.DEBUG) DebugHooks.wrapStreamSource?.invoke(source) ?: source else source

    init {
        if (BuildConfig.DEBUG) DebugHooks.onSensorHub?.invoke(this)
    }

    private val sharing = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS, 0)
    private val streams = ConcurrentHashMap<String, SharedFlow<StreamState>>()

    /** Shared `OnStreamState.StartStreaming(dataTypeId)` for any Karoo or extension data type. */
    fun stream(dataTypeId: String): Flow<StreamState> =
        streams.getOrPut(dataTypeId) { source.dataStream(dataTypeId).shareIn(scope, sharing, replay = 1) }

    val userProfile: SharedFlow<UserProfile> by lazy { source.userProfile().shareIn(scope, sharing, replay = 1) }
    val rideState: SharedFlow<RideState> by lazy { source.rideState().shareIn(scope, sharing, replay = 1) }
    val navigationState: SharedFlow<OnNavigationState> by lazy { source.navigationState().shareIn(scope, sharing, replay = 1) }
    val activeRidePage: SharedFlow<ActiveRidePage> by lazy { source.activeRidePage().shareIn(scope, sharing, replay = 1) }

    val upcomingRoute: SharedFlow<UpcomingRoute?> by lazy {
        upcomingRouteFlow(navigationState, stream(DataType.Type.DISTANCE_TO_DESTINATION)).shareIn(scope, sharing, replay = 1)
    }

    /** Whether [dataTypeId] is on the active ride page (views update only while visible). */
    fun isVisible(dataTypeId: String): Flow<Boolean> =
        activeRidePage.map { page -> page.page.elements.any { it.dataTypeId == dataTypeId } }.distinctUntilChanged()

    companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
