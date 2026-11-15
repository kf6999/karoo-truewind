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

import android.content.Context
import io.hammerhead.karooext.extension.DataTypeImpl
import io.hammerhead.karooext.internal.Emitter
import io.hammerhead.karooext.internal.ViewEmitter
import io.hammerhead.karooext.models.StreamState
import io.hammerhead.karooext.models.ViewConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Number of this extension's data-type streams and views the Karoo currently runs. The tick
 * policy treats a field as visible only while the Karoo is actually consuming it (SPEC 4.1).
 */
object ActiveFields {
    private val _count = MutableStateFlow(0)
    val count: StateFlow<Int> = _count

    internal fun acquire(): () -> Unit {
        _count.update(1)
        val released = AtomicBoolean(false)
        return { if (released.compareAndSet(false, true)) _count.update(-1) }
    }

    private fun MutableStateFlow<Int>.update(delta: Int) {
        while (true) {
            val v = value
            if (compareAndSet(v, maxOf(0, v + delta))) return
        }
    }

    /**
     * Wraps [inner] so its streams are counted; behaviour is otherwise unchanged. Views are not
     * counted (karoo-ext's `ViewEmitter` is final); the active ride page covers graphical fields.
     */
    fun track(inner: DataTypeImpl): DataTypeImpl = object : DataTypeImpl(inner.extension, inner.typeId) {
        override fun startStream(emitter: Emitter<StreamState>) = inner.startStream(TrackedEmitter(emitter, acquire()))
        override fun startView(context: Context, config: ViewConfig, emitter: ViewEmitter) = inner.startView(context, config, emitter)
    }

    internal class TrackedEmitter<T>(private val d: Emitter<T>, private val release: () -> Unit) : Emitter<T> by d {
        override fun setCancellable(cancellable: () -> Unit) = d.setCancellable { release(); cancellable() }
        override fun cancel() { release(); d.cancel() }
    }
}
