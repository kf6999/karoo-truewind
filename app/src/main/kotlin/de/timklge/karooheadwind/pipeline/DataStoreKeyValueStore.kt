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

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import de.timklge.karooheadwind.KarooHeadwindExtension
import io.github.kf6999.truewind.core.fusion.KeyValueStore
import kotlinx.coroutines.flow.first

/**
 * Engine state (member weights per ~40 km cell, virtual-sensor bias per bike). Kept in its own
 * DataStore file so these writes never wake the settings flow observers.
 */
val Context.engineDataStore: DataStore<Preferences> by preferencesDataStore(name = "truewind_engine", corruptionHandler = ReplaceFileCorruptionHandler {
    Log.w(KarooHeadwindExtension.TAG, "Engine store corrupt, starting empty")
    emptyPreferences()
})

class DataStoreKeyValueStore(private val store: DataStore<Preferences>) : KeyValueStore {
    override suspend fun get(key: String): String? = try {
        store.data.first()[stringPreferencesKey(key)]
    } catch (e: Exception) {
        null
    }

    override suspend fun put(key: String, value: String) {
        try {
            store.edit { it[stringPreferencesKey(key)] = value }
        } catch (e: Exception) {
            Log.w(KarooHeadwindExtension.TAG, "Engine store write failed", e)
        }
    }
}
