package com.u1.slicer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * Owns the most-recent in-progress Prepare-screen session for F89.
 * Single DataStore key `session_state_json` in the shared `u1_slicer_settings`
 * DataStore. Writes are atomic per `edit { }` contract. The repository is
 * thin — all state-transition logic is on [SessionState] as pure helpers
 * so it's JVM-unit-testable.
 */
class SessionStateRepository(private val context: Context) {

    private val key = stringPreferencesKey(KEY_NAME)

    val state: Flow<SessionState?> = context.appDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) null else SessionState.fromJson(raw)
    }

    suspend fun read(): SessionState? = state.first()

    suspend fun write(state: SessionState) {
        context.appDataStore.edit { prefs ->
            prefs[key] = SessionState.toJson(state)
        }
    }

    suspend fun clear() {
        context.appDataStore.edit { prefs ->
            prefs.remove(key)
        }
    }

    companion object {
        const val KEY_NAME = "session_state_json"
    }
}
