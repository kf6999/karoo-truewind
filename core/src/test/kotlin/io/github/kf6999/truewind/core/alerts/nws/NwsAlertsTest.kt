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

package io.github.kf6999.truewind.core.alerts.nws

import io.github.kf6999.truewind.core.api.HttpResponse
import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.providers.obs.FakeHttpClient
import io.github.kf6999.truewind.core.providers.obs.NWS_USER_AGENT
import io.github.kf6999.truewind.core.providers.obs.fixture
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class NwsAlertsTest {
    private val dir = "alerts/nws"
    private fun ms(iso: String) = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    @Test fun buildUrl() {
        assertEquals(
            "https://api.weather.gov/alerts/active?point=37.7749,-122.4194",
            NwsAlerts.buildUrl(GeoPoint(37.774912, -122.419416)),
        )
    }

    @Test fun parse_sf() {
        val alerts = requireNotNull(NwsAlerts.parse(fixture("$dir/alerts_sf.json")))
        assertEquals(2, alerts.size)
        val warning = alerts[0]
        assertEquals("High Surf Warning", warning.event)
        assertEquals(AlertSeverity.MODERATE, warning.severity)
        assertEquals(AlertUrgency.EXPECTED, warning.urgency)
        assertEquals("Likely", warning.certainty)
        assertTrue(warning.id.startsWith("urn:oid:"))
        assertEquals(ms("2026-11-15T00:29:00-08:00"), warning.onsetMs)
        assertEquals(ms("2026-11-15T04:00:00-08:00"), warning.endsMs)
        assertEquals(ms("2026-11-15T08:30:00-08:00"), warning.expiresMs)
        assertEquals("Update", warning.messageType)
        assertFalse(warning.hasGeometry)
        assertTrue(warning.headline!!.startsWith("High Surf Warning issued"))
        assertFalse(warning.severity.isSevereOrWorse)
    }

    @Test fun parse_severeWithGeometry() {
        val alerts = requireNotNull(NwsAlerts.parse(fixture("$dir/alerts_severe.json")))
        assertEquals(3, alerts.size)
        assertTrue(alerts.all { it.severity == AlertSeverity.SEVERE && it.severity.isSevereOrWorse })
        val marine = alerts.single { it.event == "Special Marine Warning" }
        assertTrue(marine.hasGeometry)
        assertEquals(AlertUrgency.IMMEDIATE, marine.urgency)
    }

    @Test fun parse_emptyAndJunk() {
        assertEquals(emptyList<NwsAlert>(), NwsAlerts.parse(fixture("$dir/alerts_empty.json")))
        assertNull(NwsAlerts.parse("nope".toByteArray()))
        assertNull(NwsAlerts.parse("""{"type":"x"}""".toByteArray()))
        val odd = requireNotNull(
            NwsAlerts.parse(
                """{"features":[null,{"properties":{"id":"a","event":"Test","severity":"weird","onset":"bad","ends":null,"new":1}},
                {"properties":{"id":"b"}}]}""".toByteArray(),
            ),
        )
        assertEquals(1, odd.size)
        assertEquals(AlertSeverity.UNKNOWN, odd[0].severity)
        assertEquals(AlertUrgency.UNKNOWN, odd[0].urgency)
        assertNull(odd[0].onsetMs)
        assertNull(odd[0].endsMs)
    }

    @Test fun isActiveAt() {
        val a = requireNotNull(NwsAlerts.parse(fixture("$dir/alerts_sf.json")))[1] // advisory 04:00 -> 17th 04:00 PST
        assertFalse(a.isActiveAt(ms("2026-11-15T03:59:00-08:00")))
        assertTrue(a.isActiveAt(ms("2026-11-15T04:00:00-08:00")))
        assertFalse(a.isActiveAt(ms("2026-11-17T04:00:00-08:00")))
        val open = NwsAlert("x", "E", AlertSeverity.SEVERE, AlertUrgency.IMMEDIATE)
        assertTrue(open.isActiveAt(Instant.parse("2030-01-01T00:00:00Z").toEpochMilli()))
    }

    @Test fun fetch_filtersNonActualAndCancelled() = runBlocking {
        val body = """{"type":"FeatureCollection","features":[
            {"properties":{"id":"1","event":"Tornado Warning","severity":"Extreme","urgency":"Immediate","status":"Actual","messageType":"Alert"}},
            {"properties":{"id":"2","event":"Test Message","severity":"Unknown","status":"Test","messageType":"Alert"}},
            {"properties":{"id":"3","event":"Flood Warning","severity":"Severe","status":"Actual","messageType":"Cancel"}}]}"""
        val http = FakeHttpClient().on("/alerts/active?point=") { HttpResponse(200, body = body.toByteArray()) }
        val r = NwsAlerts.fetch(http, GeoPoint(35.0, -97.0))
        assertEquals(listOf("1"), r.items.map { it.id })
        assertEquals(AlertSeverity.EXTREME, r.items[0].severity)
        assertEquals(NWS_USER_AGENT, http.requests.single().headers["User-Agent"])
    }

    @Test fun fetch_errors() = runBlocking {
        val r = NwsAlerts.fetch(FakeHttpClient().on("") { HttpResponse(500) }, GeoPoint(35.0, -97.0))
        assertEquals(500, r.httpStatus)
        val t = NwsAlerts.fetch(FakeHttpClient().on("") { throw java.io.IOException("down") }, GeoPoint(35.0, -97.0))
        assertEquals("down", t.error)
    }
}
