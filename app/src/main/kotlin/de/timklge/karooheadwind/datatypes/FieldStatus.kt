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

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.appwidget.background
import androidx.glance.appwidget.cornerRadius
import androidx.glance.color.ColorProvider
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import de.timklge.karooheadwind.pipeline.WindSnapshot
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import io.github.kf6999.truewind.core.fusion.ConfidenceBand
import io.github.kf6999.truewind.core.model.Status

/**
 * The small corner badge every graphical field shows when its numbers should not be trusted at a
 * glance: cached data while offline, stale data, or a low-confidence wind estimate.
 */
object FieldStatus {
    /** Badge text, or null when the data is current and trustworthy. */
    fun badge(s: WindSnapshot?): String? {
        if (s == null || s.isPlaceholder) return null
        s.extras.offline?.let { return "OFFLINE ${age(it.ageMs)}" }
        if (s.state.status == Status.STALE) return s.state.ageMs?.let { "OLD ${age(it)}" } ?: "OLD"
        if (!s.extras.upstreamMode && s.extras.band == ConfidenceBand.LOW) return "LOW CONF"
        return null
    }

    /** `45m` under an hour, `3h` above. */
    fun age(ageMs: Long): String {
        val min = (ageMs / 60_000L).coerceAtLeast(0)
        return if (min < 60) "${min}m" else "${min / 60}h"
    }
}

/** Overlays [text] as a red pill in the top-right corner; draw it last inside a full-size Box. */
@Composable
fun StatusBadge(text: String?) {
    if (text == null) return
    Box(modifier = GlanceModifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            maxLines = 1,
            style = TextStyle(color = ColorProvider(Color.White, Color.White), fontSize = 11.sp, fontWeight = FontWeight.Bold),
            modifier = GlanceModifier.background(Color(0xFFC62828), Color(0xFFC62828))
                .cornerRadius(4.dp).padding(horizontal = 3.dp, vertical = 0.dp),
        )
    }
}

/** [FieldStatus.badge] of the live snapshot; starts with null so views render before the first snapshot. */
fun de.timklge.karooheadwind.pipeline.WindRepository.badgeFlow(): kotlinx.coroutines.flow.Flow<String?> = kotlinx.coroutines.flow.flow {
    emit(null)
    emitAll(readySnapshot.map { FieldStatus.badge(it) }.distinctUntilChanged())
}
