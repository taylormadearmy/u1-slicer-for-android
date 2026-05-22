package com.u1.slicer.data

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.printersDataStore by preferencesDataStore(name = "printers_config")

/**
 * Owns the list of configured printers and the active-printer id.
 * Single DataStore key "printers_config_json" holds the entire PrintersConfig as JSON.
 *
 * The state-transition logic is exposed as pure companion-object helpers
 * (applyAdd / applyUpdate / applyDelete / applySetActive) so it can be
 * unit-tested without DataStore. The instance methods wrap them with the
 * DataStore.edit { } write.
 */
class PrintersRepository(private val context: Context) {

    private val key = stringPreferencesKey(KEY_NAME)

    val config: Flow<PrintersConfig?> = context.printersDataStore.data.map { prefs ->
        val raw = prefs[key]
        if (raw.isNullOrBlank()) null else runCatching { PrintersConfig.fromJson(raw) }.getOrNull()
    }

    /** Convenience: emits the active printer once config exists. */
    val activePrinter: Flow<Printer?> = config.map { it?.active }

    suspend fun add(printer: Printer) = mutate { applyAdd(it, printer) }
    suspend fun update(printer: Printer) = mutate { applyUpdate(it, printer) }
    suspend fun delete(id: String) = mutate { applyDelete(it, id) }
    suspend fun setActive(id: String) = mutate { applySetActive(it, id) }

    /** Overwrite the whole config. Used by migration and import paths. */
    suspend fun replace(cfg: PrintersConfig) {
        context.printersDataStore.edit { prefs ->
            prefs[key] = PrintersConfig.toJson(cfg)
        }
    }

    private suspend fun mutate(transform: (PrintersConfig) -> PrintersConfig) {
        context.printersDataStore.edit { prefs ->
            val raw = prefs[key] ?: return@edit
            val current = runCatching { PrintersConfig.fromJson(raw) }.getOrNull() ?: return@edit
            val next = transform(current)
            if (next !== current) {
                prefs[key] = PrintersConfig.toJson(next)
            }
        }
    }

    companion object {
        const val KEY_NAME = "printers_config_json"
        private const val TAG = "PrintersRepository"

        fun applyAdd(cfg: PrintersConfig, p: Printer): PrintersConfig =
            cfg.copy(printers = cfg.printers + p)

        fun applyUpdate(cfg: PrintersConfig, p: Printer): PrintersConfig =
            cfg.copy(printers = cfg.printers.map { if (it.id == p.id) p else it })

        fun applyDelete(cfg: PrintersConfig, id: String): PrintersConfig {
            check(id != cfg.activeId) {
                "Cannot delete the active printer (id=$id). Switch to another printer first."
            }
            check(cfg.printers.size > 1) {
                "Cannot delete the last remaining printer (id=$id)."
            }
            return cfg.copy(printers = cfg.printers.filterNot { it.id == id })
        }

        fun applySetActive(cfg: PrintersConfig, id: String): PrintersConfig {
            if (cfg.printers.none { it.id == id }) {
                Log.w(TAG, "setActive called with unknown id='$id'; ignoring")
                return cfg
            }
            if (id == cfg.activeId) return cfg
            return cfg.copy(activeId = id)
        }
    }
}
