package com.u1.slicer.aipaint

import android.graphics.Bitmap

data class AiRegion(
    val id: Int,                     // 0–3
    val label: String,               // e.g. "Head & face"
    val suggestedColour: String,     // hex "#RRGGBB"
    val userColour: String? = null,  // null = use suggestedColour
    val coverageFraction: Float = 0f // 0.0–1.0
) {
    val effectiveColour: String get() = userColour ?: suggestedColour
}

data class AiPaintResultState(
    val regions: List<AiRegion>,
    val paintedModelPath: String,
    val sourceModelPath: String,
    val previewBitmap: Bitmap? = null // front shaded render for result screen
)

enum class AiPaintProvider(val displayName: String, val requiresKey: Boolean) {
    POLLINATIONS("Pollinations.ai (free, no key)", false),
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
    object Idle : AiPaintUiState()
    data class Running(val phase: Int, val phaseLabel: String) : AiPaintUiState()
    data class Result(val state: AiPaintResultState) : AiPaintUiState()
    data class Error(val message: String) : AiPaintUiState()
}
