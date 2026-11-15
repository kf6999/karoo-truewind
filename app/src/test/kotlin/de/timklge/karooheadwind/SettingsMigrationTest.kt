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

package de.timklge.karooheadwind

import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Upstream karoo-headwind settings JSON still decodes, and the new fields get their defaults. */
class SettingsMigrationTest {
    /** As upstream wrote it (no TrueWind fields; one key this build does not know). */
    private val upstreamJson = """
        {"welcomeDialogAccepted":true,"roundLocationTo":"KM_3","forecastedKmPerHour":25,"forecastedMilesPerHour":15,
         "showDistanceInForecast":false,"weatherProvider":"OPEN_METEO","openWeatherMapApiKey":"",
         "refreshRate":"STANDARD","windUnit":"KNOTS","someRemovedUpstreamSetting":42}
    """.trimIndent()

    @Test
    fun upstreamJson_keepsUserValues_andDefaultsNewFields() {
        val s = jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(upstreamJson)
        assertTrue(s.welcomeDialogAccepted)
        assertEquals(RoundLocationSetting.KM_3, s.roundLocationTo, "an explicit user choice is kept")
        assertEquals(25, s.forecastedKmPerHour)
        assertEquals(WindUnit.KNOTS, s.windUnit)
        assertFalse(s.upstreamCompatibleMode)
        assertEquals(10, s.refreshIntervalMin)
        assertEquals(ExposureSetting.MIXED, s.exposure)
        assertEquals(1.2, s.riderHeightM)
        assertEquals(0.32, s.cda)
        assertEquals(0.005, s.crr)
        assertTrue(s.layerEnsemble && s.layerObservations && s.layerDownscaling && s.layerHeadingFusion && s.layerVirtualSensor)
        assertEquals("", s.synopticToken)
        assertEquals("", s.tomorrowApiKey)
    }

    @Test
    fun freshInstall_defaultsToOneKmRounding() {
        val s = jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(HeadwindSettings.defaultSettings)
        assertEquals(RoundLocationSetting.KM_1, s.roundLocationTo)
        assertEquals(0, RoundLocationSetting.EXACT.km)
    }

    @Test
    fun roundTrip_preservesNewFields() {
        val s = HeadwindSettings(welcomeDialogAccepted = true, refreshIntervalMin = 5, exposure = ExposureSetting.URBAN_FOREST, cda = 0.28,
            groupRide = true, layerVirtualSensor = false, synopticToken = "tok", tomorrowApiKey = "key")
        val back = jsonWithUnknownKeys.decodeFromString<HeadwindSettings>(Json.encodeToString(s))
        assertEquals(s, back)
    }

    @Test
    fun toString_neverPrintsKeys() {
        val text = HeadwindSettings(synopticToken = "secret-token-value", tomorrowApiKey = "secret-key-value", openWeatherMapApiKey = "owm-secret").toString()
        assertFalse("secret-token-value" in text)
        assertFalse("secret-key-value" in text)
        assertFalse("owm-secret" in text)
    }
}
