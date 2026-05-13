package com.u1.slicer.aipaint

import android.graphics.Bitmap

data class AiRegion(
    val id: Int,
    val label: String,
    val suggestedColour: String,       // hex "#RRGGBB"
    val userColour: String? = null,
    val coverageFraction: Float = 0f,  // 0.0–1.0
    val componentIds: List<Int> = emptyList(),  // topology components that map to this region
    // Which physical filament slot (0..3) this AI segment is currently assigned to. With N>4
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
    val previewBitmap: Bitmap? = null, // legacy front shaded render; null when 3D viewer is used
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
    // True when the AI didn't return usable region boxes and the Z-band fallback was used.
    // Surfaces in the result screen as a banner so the user knows to try a different provider.
    val usedAiFallback: Boolean = false,
    // Human-readable explanation when usedAiFallback = true. Empty string when no fallback.
    val fallbackReason: String = "",
    // True when the undo stack has at least one snapshot to restore. Enables the Undo button.
    val canUndo: Boolean = false,
    // Snapshots of both segmentation variants taken at pipeline-complete time. The result
    // screen has an "🤖 AI / 📏 Height-based" toggle that swaps the working copy
    // (regions + triangleRegions) to one of these snapshots. Brush / move edits live in
    // the working copy and are lost on toggle (intentional — toggle is a comparison tool,
    // not a workflow merge).
    val aiTriangleRegions: ByteArray? = null,
    val aiRegions: List<AiRegion> = emptyList(),
    val zBandTriangleRegions: ByteArray? = null,
    val zBandRegions: List<AiRegion> = emptyList(),
    // True when the user has toggled the view to the Z-band variant. False = AI variant.
    val showingZBands: Boolean = false,
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
    /** When true this provider operates on raw 3D point clouds via the Find3D HuggingFace
     *  Space, not on 2D rendered images. Bypasses the bounding-box-prompt path entirely. */
    val isFind3D: Boolean = false,
) {
    POLLINATIONS("Pollinations.ai (free; key optional, lifts rate limits)", false, acceptsKey = true),
    GEMINI("Google Gemini (free 1k/day)", true),
    OPENROUTER("OpenRouter", true),
    CLAUDE("Claude (Anthropic)", true),
    OPENAI("OpenAI", true),
    FIND3D("Find3D (3D-native; best for figurines)", requiresKey = false, acceptsKey = true, isFind3D = true);

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
