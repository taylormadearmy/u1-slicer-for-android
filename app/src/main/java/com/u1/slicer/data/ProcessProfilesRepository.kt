package com.u1.slicer.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.processProfilesDataStore by preferencesDataStore(name = "process_profiles_config")

/**
 * F87: Owns the list of imported process profiles + the currently active profile id.
 * Single DataStore key holds the entire [ProcessProfilesConfig] as JSON, modelled on
 * [PrintersRepository].
 *
 * State-transition logic is exposed as pure companion-object helpers so it can be unit-tested
 * without DataStore.
 */
class ProcessProfilesRepository(private val context: Context) {

    private val key = stringPreferencesKey(KEY_NAME)

    val config: Flow<ProcessProfilesConfig> = context.processProfilesDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) ProcessProfilesConfig()
        else runCatching { ProcessProfilesConfig.fromJson(raw) }.getOrDefault(ProcessProfilesConfig())
    }

    val activeProfile: Flow<ProcessProfile?> = config.map { it.active }

    suspend fun add(profile: ProcessProfile) = mutate { applyAdd(it, profile) }
    suspend fun rename(id: String, name: String) = mutate { applyRename(it, id, name) }
    suspend fun delete(id: String) = mutate { applyDelete(it, id) }
    suspend fun setActive(id: String?) = mutate { applySetActive(it, id) }

    /** Overwrite the whole config. Used by backup-import paths. */
    suspend fun replace(cfg: ProcessProfilesConfig) {
        context.processProfilesDataStore.edit { prefs ->
            prefs[key] = ProcessProfilesConfig.toJson(cfg)
        }
    }

    private suspend fun mutate(transform: (ProcessProfilesConfig) -> ProcessProfilesConfig) {
        context.processProfilesDataStore.edit { prefs ->
            val raw = prefs[key]
            val current = if (raw.isNullOrBlank()) ProcessProfilesConfig()
                          else runCatching { ProcessProfilesConfig.fromJson(raw) }.getOrDefault(ProcessProfilesConfig())
            val next = transform(current)
            if (next != current) {
                prefs[key] = ProcessProfilesConfig.toJson(next)
            }
        }
    }

    companion object {
        const val KEY_NAME = "process_profiles_config_json"

        fun applyAdd(cfg: ProcessProfilesConfig, p: ProcessProfile): ProcessProfilesConfig {
            val list = cfg.profiles + p
            // If this is the first profile imported, do NOT auto-activate — the user opts in
            // via the Prepare-screen dropdown.
            return cfg.copy(profiles = list)
        }

        fun applyRename(cfg: ProcessProfilesConfig, id: String, name: String): ProcessProfilesConfig {
            if (name.isBlank()) return cfg
            return cfg.copy(profiles = cfg.profiles.map { p ->
                if (p.id == id) p.copy(name = name.trim()) else p
            })
        }

        fun applyDelete(cfg: ProcessProfilesConfig, id: String): ProcessProfilesConfig {
            val next = cfg.profiles.filterNot { it.id == id }
            // Clear activeId if the active profile was just deleted.
            val nextActive = if (cfg.activeId == id) null else cfg.activeId
            return ProcessProfilesConfig(profiles = next, activeId = nextActive)
        }

        fun applySetActive(cfg: ProcessProfilesConfig, id: String?): ProcessProfilesConfig {
            if (id == null) return cfg.copy(activeId = null)
            if (cfg.profiles.none { it.id == id }) return cfg  // ignore invalid id
            return cfg.copy(activeId = id)
        }
    }
}
