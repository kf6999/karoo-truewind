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

import android.widget.RemoteViews
import de.timklge.karooheadwind.pipeline.Diagnostics
import io.hammerhead.karooext.internal.ViewEmitter
import kotlin.math.abs

/** `updateView` with the per-field view-update counter (TESTING 5.1). */
fun ViewEmitter.updateViewCounted(dataTypeId: String, view: RemoteViews) {
    Diagnostics.countViewUpdate(dataTypeId)
    updateView(view)
}

/**
 * Emit-on-change (SPEC 3 rule 4): a value is emitted only when it moved by at least [threshold]
 * from the last emitted value (circular difference for [angle] values), when it appears or
 * disappears, or when its [band][shouldEmit] changes. [threshold] 0 means "any change".
 *
 * Thresholds used: speeds 0.5 display unit, directions 3 degrees, confidence band change.
 */
class EmitGate(private val threshold: Double = 0.0, private val angle: Boolean = false) {
    private var hasEmitted = false
    private var last: Double? = null
    private var lastBand: Any? = null

    fun shouldEmit(value: Double?, band: Any? = null): Boolean {
        val changed = when {
            !hasEmitted -> true
            band != lastBand -> true
            (value == null) != (last == null) -> true
            value == null -> false
            else -> {
                val d = if (angle) circularDiff(value, last!!) else abs(value - last!!)
                if (threshold <= 0.0) value != last else d >= threshold - 1e-9
            }
        }
        if (changed) {
            hasEmitted = true
            last = value
            lastBand = band
        }
        return changed
    }

    companion object {
        const val SPEED_THRESHOLD = 0.5
        const val ANGLE_THRESHOLD = 3.0

        fun circularDiff(a: Double, b: Double): Double {
            val d = abs(((a - b) % 360.0 + 540.0) % 360.0 - 180.0)
            return d
        }
    }
}

/**
 * At most one view update per [minIntervalMs] per field (SPEC 3 rule 4). Callers also skip
 * updates entirely while the field is not visible.
 */
class ViewRateLimiter(private val minIntervalMs: Long = 1_000L) {
    private var lastMs = Long.MIN_VALUE

    /** Milliseconds to wait before the next update is allowed (0 = now). */
    fun waitMs(nowMs: Long): Long = if (lastMs == Long.MIN_VALUE) 0 else (lastMs + minIntervalMs - nowMs).coerceAtLeast(0)

    fun tryAcquire(nowMs: Long): Boolean {
        if (lastMs != Long.MIN_VALUE && nowMs - lastMs < minIntervalMs) return false
        lastMs = nowMs
        return true
    }
}
