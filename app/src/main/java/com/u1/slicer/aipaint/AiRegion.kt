package com.u1.slicer.aipaint

import android.graphics.Bitmap

data class AiRegion(
    val id: Int,
    val label: String,
    val suggestedColour: String,       // hex "#RRGGBB"
    val userColour: String? = null,
    val coverageFraction: Float = 0f,  // 0.0–1.0
    val componentIds: List<Int> = emptyList(),  // band indices that map to this region
    // Which physical filament slot (0..3) this segment is currently assigned to. With N > 4
    // segments multiple semantic regions fold onto 4 U1 slots; the slot picker on each row
    // lets the user change this. Default = `id % 4` (round-robin).
    val slot: Int = 0,
) {
    val effectiveColour: String get() = userColour ?: suggestedColour
}

data class AiPaintResultState(
    val regions: List<AiRegion>,
    val paintedModelPath: String,
    val sourceModelPath: String,
    val previewBitmap: Bitmap? = null,
    // Persisted topology data so users can move components between regions interactively.
    val trianglePositions: FloatArray = FloatArray(0),
    val componentIds: IntArray = IntArray(0),
    val numComponents: Int = 0,
    val componentToRegion: IntArray = IntArray(0),
    // Per-triangle SLOT assignment (0..TARGET_SLOTS-1). This is what gets written to the
    // painted 3MF as paint_color. Initially derived from segments[triangleSegments[t]].slot;
    // the brush mutates it directly so individual triangles can override their segment's slot.
    val triangleRegions: ByteArray = ByteArray(0),
    // Per-triangle SEGMENT assignment (0..TARGET_SEGMENTS-1). Immutable after pipeline run.
    // Each segment is one row in the result list and has its own slot mapping (see
    // `regions[i].slot`). Mass-updating a segment's slot rewrites triangleRegions for every
    // triangle in that segment.
    val triangleSegments: ByteArray = ByteArray(0),
    // When non-null, the 3D view highlights this single component and dims the rest.
    val highlightComponentId: Int? = null,
    // True when the undo stack has at least one snapshot to restore. Enables the Undo button.
    val canUndo: Boolean = false,
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
