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
    /** Root-level nodes. Today the cascade emits exactly one cascade root + an optional
     *  "Custom selections" sibling. Tree is the source of truth for what the screen renders. */
    val tree: List<AiRegionNode>,
    /** Which cascade branch produced the segmentation. */
    val source: SegmentationSource,

    val paintedModelPath: String,
    val sourceModelPath: String,
    val previewBitmap: Bitmap? = null,

    val trianglePositions: FloatArray = FloatArray(0),
    /** Per-triangle SEGMENT id (matches the cascade-tree leaf id that originally claimed the
     *  triangle; mutated only when the user does a "Clear all custom selections" flatten). */
    val triangleSegments: ByteArray = ByteArray(0),
    /** Per-triangle SLOT (0..3). Mutated by paint/lasso/cascade-reassign. Written to the 3MF. */
    val triangleRegions: ByteArray = ByteArray(0),

    // When non-null, the 3D view highlights this single component and dims the rest.
    val highlightComponentId: Int? = null,
    // True when the undo stack has at least one snapshot to restore. Enables the Undo button.
    val canUndo: Boolean = false,

    /** Optional AI-naming side state — drives the failure chip. */
    val aiNamingFailed: Boolean = false,
    val aiModelTried: String? = null,

    /** Brush / lasso commits accumulated in this session. Rendered as a root-level group. */
    val customSelections: List<CustomSelection> = emptyList(),

    /** fix38: cached alternate view (typically topology) the user can switch to. Null when no
     *  alternate is available for this model (e.g. cascade fired Branch E or F directly, so
     *  there's nothing to toggle to). Switching swaps tree/source/triangleSegments with
     *  alternate*; user edits (triangleRegions / customSelections / undo stack) are reset on
     *  switch — the alternate is a fresh start, not a layered overlay. */
    val alternateTree: List<AiRegionNode>? = null,
    val alternateSource: SegmentationSource? = null,
    val alternateTriangleSegments: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean = this === other
    override fun hashCode(): Int = System.identityHashCode(this)

    /** Convenience: flat list of leaf regions, used by code that wants to iterate everything
     *  the user can recolour. Skips the cascade root and the "Custom selections" group parent. */
    val leafRegions: List<AiRegion>
        get() = tree.flatMap { root ->
            root.flatten().filter { (n, _) -> n.isLeaf }.map { it.first.region }
        }

    /** Stub members for old call sites — empty until the cascade comes online. The ViewModel's
     *  setSegmentSlot / moveComponent path uses these temporarily; rewired in Task 12. */
    val regions: List<AiRegion> get() = leafRegions
    val componentIds: IntArray get() = IntArray(0)
    val numComponents: Int get() = leafRegions.size
    val componentToRegion: IntArray get() = IntArray(0)
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
