package com.u1.slicer.data

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class LibraryState {
    object Loading : LibraryState()
    data class Ready(val library: FilamentLibrary) : LibraryState()
    data class Failed(val message: String) : LibraryState()
}

/**
 * Owns the bundled filament library: lazy one-shot asset load off the main
 * thread, plus favourites/recents persistence (slug lists in DataStore).
 */
class FilamentLibraryRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val loadStarted = AtomicBoolean(false)

    val favourites: Flow<List<String>> = settings.filamentLibraryFavourites
    val recents: Flow<List<String>> = settings.filamentLibraryRecents

    /** Idempotent — first caller wins; later calls are no-ops unless retrying a failure. */
    fun ensureLoaded(scope: CoroutineScope) {
        if (!loadStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { load() }
    }

    fun retry(scope: CoroutineScope) {
        _state.value = LibraryState.Loading
        scope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        _state.value = try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            LibraryState.Ready(FilamentLibrary.parse(text))
        } catch (e: Exception) {
            LibraryState.Failed(e.message ?: "Failed to load filament library")
        }
    }

    suspend fun toggleFavourite(slug: String) {
        val current = settings.filamentLibraryFavourites.first()
        val updated = if (slug in current) current - slug else current + slug
        settings.setFilamentLibraryFavourites(updated)
    }

    suspend fun recordRecent(slug: String) {
        settings.setFilamentLibraryRecents(updateRecents(settings.filamentLibraryRecents.first(), slug))
    }

    companion object {
        const val ASSET_NAME = "filament_library.json"
        const val MAX_RECENTS = 10
    }
}

/** Most-recent-first, deduplicated, capped at [FilamentLibraryRepository.MAX_RECENTS]. */
internal fun updateRecents(current: List<String>, slug: String): List<String> =
    (listOf(slug) + current.filterNot { it == slug }).take(FilamentLibraryRepository.MAX_RECENTS)
