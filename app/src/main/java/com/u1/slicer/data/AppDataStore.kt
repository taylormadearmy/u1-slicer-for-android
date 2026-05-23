package com.u1.slicer.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Shared `u1_slicer_settings` DataStore extension. Owned at package level so
 * `SettingsRepository` and `SessionStateRepository` reference the same backing
 * file via `context.appDataStore`. `preferencesDataStore` must only be invoked
 * once per store name per process — invoking twice with the same name silently
 * creates two racing `DataStore` instances over the same file.
 */
internal val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "u1_slicer_settings")
