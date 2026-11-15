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

package io.github.kf6999.truewind.core.time

/** Wall-clock source. Inject it everywhere time matters so tests and the replay harness can control it. */
fun interface Clock {
    /** Milliseconds since the unix epoch. */
    fun nowMs(): Long

    companion object {
        val SYSTEM: Clock = Clock { System.currentTimeMillis() }
    }
}

/** A clock that only moves when told to. For tests and replay. */
class ManualClock(private var now: Long = 0L) : Clock {
    override fun nowMs(): Long = now

    fun set(ms: Long) { now = ms }

    fun advanceBy(ms: Long) { now += ms }
}
