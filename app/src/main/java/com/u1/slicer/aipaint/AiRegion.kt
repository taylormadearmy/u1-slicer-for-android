package com.u1.slicer.aipaint

import android.graphics.Bitmap

data class AiRegion(
    val id: Int,
    val label: String,
    val suggestedColour: String,       // hex "#RRGGBB"
    val userColour: String? = null,
    val coverageFraction: Float = 0f,  // 0.0–1.0
    val componentIds: List<Int> = emptyList()  // topology components that map to this region
) {
    val effectiveColour: String get() = userColour ?: suggestedColour
}

data class AiPaintResultState(
    val regions: List<AiRegion>,
    val paintedModelPath: String,
    val sourceModelPath: String,
    val previewBitmap: Bitmap? = null, // legacy front shaded render; null when 3D viewer is used
    // Persisted topology data so users can move components between regions interactively.
    val trianglePositions: FloatArray = FloatArray(0),
    val componentIds: IntArray = IntArray(0),
    val numComponents: Int = 0,
    val componentToRegion: IntArray = IntArray(0),
    // When non-null, the 3D view highlights this single component and dims the rest.
    val highlightComponentId: Int? = null,
    // True when the AI didn't return usable region boxes and the Z-band fallback was used.
    // Surfaces in the result screen as a banner so the user knows to try a different provider.
    val usedAiFallback: Boolean = false,
) {
    // data class equals/hashCode default would compare arrays by reference; we don't rely on
    // equality of result state beyond identity, so we override to suppress warnings.
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)
}

enum class AiPaintProvider(
    val displayName: String,
    val requiresKey: Boolean,
    /** When true the settings UI exposes an API key field for this provider. Providers where
     *  `requiresKey = false` but `acceptsKey = true` work anonymously but accept a key to lift
     *  rate limits or unlock priority routing — Pollinations is the current example. */
    val acceptsKey: Boolean = requiresKey,
) {
    POLLINATIONS("Pollinations.ai (free; key optional, lifts rate limits)", false, acceptsKey = true),
    GEMINI("Google Gemini (free 1k/day)", true),
    OPENROUTER("OpenRouter", true),
    CLAUDE("Claude (Anthropic)", true),
    OPENAI("OpenAI", true);

    companion object {
        val DEFAULT = POLLINATIONS
        fun fromId(id: String): AiPaintProvider =
            entries.firstOrNull { it.name == id } ?: DEFAULT
    }
}

sealed class AiPaintUiState {
    data object Idle : AiPaintUiState()
    data class Running(val phase: Int, val phaseLabel: String) : AiPaintUiState()
    data class Result(val state: AiPaintResultState) : AiPaintUiState()
    data class Error(val message: String) : AiPaintUiState()
}
