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

package io.github.kf6999.truewind.core.route

import io.github.kf6999.truewind.core.model.GeoPoint
import io.github.kf6999.truewind.core.time.ManualClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FinishAlertTest {
    private val t0 = 1_800_000_000_000L - (1_800_000_000_000L % 3_600_000L)
    private val min = 60_000L
    private val base = FinishAlertConfig(enabled = true, url = "https://ntfy.sh/my-topic", thresholdMin = 30, message = "Put the kettle on", zone = ZoneOffset.UTC)

    /** Simulates a ride: time to destination falls 1 min per minute from [startTtdMin]; returns (minute, request) pairs. */
    private fun ride(m: FinishAlertMachine, clock: ManualClock, startTtdMin: Int, rideId: String = "r1", success: Boolean = true): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        for (i in 0..startTtdMin) {
            val req = m.onUpdate(clock.nowMs(), rideId, (startTtdMin - i) * min, GeoPoint(40.123456, -105.987654))
            if (req != null) {
                out += i to String(req.body!!)
                m.onSendResult(success, clock.nowMs())
            }
            clock.advanceBy(min)
        }
        return out
    }

    @Test
    fun firesExactlyOncePerRideAfterThreshold() {
        val clock = ManualClock(t0)
        val m = FinishAlertMachine(base)
        val sent = ride(m, clock, 90)
        // Below 30 min first at minute 61 (ttd 29), debounced 60 s: sent at minute 62 (ttd 28).
        assertEquals(1, sent.size)
        assertEquals(62, sent[0].first)
        assertEquals("ETA 09:30 (about 28 min). Put the kettle on" /* t0 is 08:00 UTC; minute 62 + 28 min */, sent[0].second)
        assertEquals(FinishAlertMachine.State.SENT, m.state)
        // A new ride re-arms.
        assertEquals(1, ride(m, clock, 45, rideId = "r2").size)
    }

    @Test
    fun ntfyRequestShapeAndNoLocationByDefault() {
        val req = FinishAlertMachine.buildRequest(base, t0, 15 * min, GeoPoint(40.0, -105.0))
        assertEquals("POST", req.method)
        assertEquals("https://ntfy.sh/my-topic", req.url)
        assertEquals("Ride finishing soon", req.headers["Title"])
        assertTrue(req.headers["Content-Type"]!!.startsWith("text/plain"))
        assertFalse(String(req.body!!).contains("40"), "no location unless opted in")
    }

    @Test
    fun jsonWebhookWithOptInLocation() {
        val cfg = base.copy(format = FinishAlertFormat.JSON, url = "https://example.org/hook", includeLocation = true)
        val req = FinishAlertMachine.buildRequest(cfg, t0, 15 * min, GeoPoint(40.123456, -105.987654))
        assertEquals("application/json", req.headers["Content-Type"])
        val o = Json.parseToJsonElement(String(req.body!!)).jsonObject
        assertEquals("finish_eta", o["event"]!!.jsonPrimitive.content)
        assertEquals(15, o["minutesRemaining"]!!.jsonPrimitive.content.toInt())
        assertEquals((t0 + 15 * min).toString(), o["etaMs"]!!.jsonPrimitive.content)
        assertEquals("40.12346", o["location"]!!.jsonObject["lat"]!!.jsonPrimitive.content)
        assertTrue(o["message"]!!.jsonPrimitive.content.contains("openstreetmap.org"))
    }

    @Test
    fun startingInsideThreshold() {
        val clock = ManualClock(t0)
        assertEquals(1, ride(FinishAlertMachine(base), clock, 20).size)
        assertTrue(ride(FinishAlertMachine(base.copy(fireIfStartsInside = false)), clock, 20).isEmpty())
    }

    @Test
    fun debounceIgnoresEtaBlip() {
        val m = FinishAlertMachine(base)
        assertNull(m.onUpdate(t0, "r", 40 * min))
        assertNull(m.onUpdate(t0 + 10_000, "r", 25 * min)) // optimistic blip
        assertNull(m.onUpdate(t0 + 40_000, "r", 39 * min))
        assertNull(m.onUpdate(t0 + 70_000, "r", 38 * min))
        assertNull(m.onUpdate(t0 + 100_000, "r", 29 * min))
        assertNotNull(m.onUpdate(t0 + 160_000, "r", 28 * min))
    }

    @Test
    fun retriesAfterFailureThenGivesUp() {
        val m = FinishAlertMachine(base.copy(maxRetries = 2, retryDelayMs = 60_000))
        assertNull(m.onUpdate(t0, "r", 10 * min))
        val first = assertNotNull(m.onUpdate(t0 + 60_000, "r", 9 * min))
        assertTrue(first.url.startsWith("https://"))
        m.onSendResult(false, t0 + 61_000)
        assertNull(m.onUpdate(t0 + 90_000, "r", 8 * min), "retry delay")
        assertNotNull(m.onUpdate(t0 + 121_000, "r", 8 * min))
        m.onSendResult(false, t0 + 122_000)
        assertNotNull(m.onUpdate(t0 + 182_000, "r", 7 * min))
        m.onSendResult(false, t0 + 183_000)
        assertNull(m.onUpdate(t0 + 300_000, "r", 5 * min), "3 attempts = 1 + 2 retries")
        assertEquals(FinishAlertMachine.State.FAILED, m.state)
    }

    @Test
    fun disabledOrBadUrlNeverFires() {
        val clock = ManualClock(t0)
        assertTrue(ride(FinishAlertMachine(base.copy(enabled = false)), clock, 40).isEmpty())
        assertTrue(ride(FinishAlertMachine(base.copy(url = "http://ntfy.sh/x")), clock, 40).isEmpty())
        assertTrue(ride(FinishAlertMachine(base.copy(url = "")), clock, 40).isEmpty())
        // Not navigating: no time to destination.
        val m = FinishAlertMachine(base)
        assertNull(m.onUpdate(t0, "r", null))
    }
}
