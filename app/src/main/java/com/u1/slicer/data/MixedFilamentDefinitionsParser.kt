package com.u1.slicer.data

/**
 * Where the active slice recipe came from.
 *
 * `NONE` means no recipe has been resolved yet.
 * `FILE_EMBEDDED` means the imported 3MF's embedded recipe is active.
 * `MANAGER_STATE` means the editable project rows are active.
 */
enum class MixedFilamentDefinitionSource {
    NONE,
    FILE_EMBEDDED,
    MANAGER_STATE,
}

data class ImportedMixRecipeRowSummary(
    val rawRow: String,
    val componentIds: List<Int>,
    val weights: List<Int>,
    val distributionMode: Int?,
    val topMixMode: Int?,
    val fineTopLines: Boolean,
    val ironingGlaze: Boolean,
    val parseWarning: String? = null,
) {
    val componentsLabel: String
        get() = if (componentIds.isNotEmpty()) componentIds.joinToString("+") { "E$it" } else rawRow

    val weightsLabel: String
        get() = if (weights.isNotEmpty()) weights.joinToString("/") else rawRow

    val distributionLabel: String
        get() = when (distributionMode) {
            0 -> "Cycle"
            1 -> "Dots"
            2 -> "Simple"
            else -> "Default"
        }

    val topMixLabel: String
        get() = when (topMixMode) {
            0 -> "Stripes"
            1 -> "Proportional"
            2 -> "Dither"
            3 -> "Off"
            else -> "Default"
        }

    fun toEditableRow(): MixedFilamentRow? {
        val components = if (componentIds.size in 2..4) componentIds else emptyList()
        if (components.size !in 2..4) return null

        val normalizedWeights = when {
            weights.size == components.size -> MixWeights.normalize(weights)
            components.size == 2 -> {
                val mixPercent = rawMixPercentFromRow(rawRow)
                MixWeights.normalize(listOf(100 - mixPercent, mixPercent))
            }
            else -> MixWeights.normalize(List(components.size) { 1 })
        }

        val distribution = when (distributionMode) {
            1 -> MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS
            else -> MixedFilamentRow.MixDistributionMode.LAYER_CYCLE
        }

        val topMode = when (topMixMode) {
            1 -> MixedFilamentRow.TopMixMode.PROPORTIONAL
            2 -> MixedFilamentRow.TopMixMode.DITHER
            3 -> MixedFilamentRow.TopMixMode.OFF
            else -> MixedFilamentRow.TopMixMode.STRIPES
        }

        return MixedFilamentRow(
            id = 0L,
            components = components,
            weights = normalizedWeights,
            distributionMode = distribution,
            label = MixedFilamentRow.autoLabel(components),
            inLibrary = false,
            topMixMode = topMode,
            fineTopLines = fineTopLines,
            ironingGlaze = ironingGlaze,
        )
    }
}

data class MixedFilamentSliceSummary(
    val source: MixedFilamentDefinitionSource,
    val recipe: String,
    val rows: List<ImportedMixRecipeRowSummary>,
) {
    val activeMixCount: Int get() = rows.size
    val editableRows: List<MixedFilamentRow> get() = rows.mapNotNull { it.toEditableRow() }
    val hasParseWarnings: Boolean get() = rows.any { it.parseWarning != null }

    companion object {
        fun empty() = MixedFilamentSliceSummary(
            source = MixedFilamentDefinitionSource.NONE,
            recipe = "",
            rows = emptyList(),
        )
    }
}

internal fun parseMixedFilamentDefinitions(recipe: String): List<MixedFilamentRow> =
    parseMixedFilamentRecipe(recipe).editableRows

internal fun parseMixedFilamentRecipe(recipe: String): MixedFilamentSliceSummary {
    val trimmed = recipe.trim()
    if (trimmed.isEmpty()) return MixedFilamentSliceSummary.empty()
    return MixedFilamentSliceSummary(
        source = MixedFilamentDefinitionSource.FILE_EMBEDDED,
        recipe = trimmed,
        rows = trimmed
            .split(';')
            .mapNotNull { rawRow ->
                val row = rawRow.trim()
                if (row.isEmpty()) return@mapNotNull null
                parseMixedFilamentRecipeRow(row)
            },
    )
}

internal fun resolveMixedFilamentDefinitionsForSliceDetails(
    sourceConfig: Map<String, Any>?,
    mixedFilamentManager: MixedFilamentManager,
    numPhysicalFilaments: Int,
): MixedFilamentSliceSummary {
    val embedded = sourceConfig
        ?.get("mixed_filament_definitions")
        ?.toString()
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    return if (embedded != null) {
        parseMixedFilamentRecipe(embedded).copy(
            source = MixedFilamentDefinitionSource.FILE_EMBEDDED,
        )
    } else {
        val recipe = mixedFilamentManager.serialize(numPhysicalFilaments)
        parseMixedFilamentRecipe(recipe).copy(
            source = MixedFilamentDefinitionSource.MANAGER_STATE,
            recipe = recipe,
        )
    }
}

internal fun resolveImportedMixRecipeDisplaySummary(
    source: MixedFilamentDefinitionSource,
    importedRecipe: MixedFilamentSliceSummary?,
    mixedFilamentManager: MixedFilamentManager?,
    numPhysicalFilaments: Int,
): MixedFilamentSliceSummary? {
    if (importedRecipe == null) return null
    return when (source) {
        MixedFilamentDefinitionSource.FILE_EMBEDDED,
        MixedFilamentDefinitionSource.NONE -> importedRecipe
        MixedFilamentDefinitionSource.MANAGER_STATE -> {
            val manager = mixedFilamentManager ?: return importedRecipe
            parseMixedFilamentRecipe(manager.serialize(numPhysicalFilaments)).copy(
                source = MixedFilamentDefinitionSource.MANAGER_STATE,
            )
        }
    }
}

internal fun resolveMixedFilamentDefinitionsForSlice(
    recipeSource: MixedFilamentDefinitionSource,
    importedRecipe: MixedFilamentSliceSummary?,
    mixedFilamentManager: MixedFilamentManager,
    numPhysicalFilaments: Int,
): String {
    if (recipeSource == MixedFilamentDefinitionSource.FILE_EMBEDDED) {
        val embedded = importedRecipe?.recipe?.trim().orEmpty()
        if (embedded.isNotEmpty()) return embedded
    }
    return mixedFilamentManager.serialize(numPhysicalFilaments)
}

private fun parseMixedFilamentRecipeRow(row: String): ImportedMixRecipeRowSummary {
    val tokens = row.split(',').map { it.trim() }
    val a = tokens.getOrNull(0)?.toIntOrNull()
    val b = tokens.getOrNull(1)?.toIntOrNull()
    val mixPercent = tokens.getOrNull(4)?.toIntOrNull()

    val gradientToken = tokens.firstOrNull { it == "g" || it.startsWith("g") }
    val weightToken = tokens.firstOrNull { it == "w" || it.startsWith("w") }
    val modeToken = tokens.firstOrNull { it == "m" || it.startsWith("m") }
    val topToken = tokens.firstOrNull { it == "t" || it.startsWith("t") }
    val fineToken = tokens.firstOrNull { it == "f" || it.startsWith("f") }
    val glazeToken = tokens.firstOrNull { it == "i" || it.startsWith("i") }

    val gradientIds = parseGradientIds(gradientToken)
    val componentIds = when {
        gradientIds.size in 2..4 -> gradientIds
        a != null && b != null -> listOf(a, b)
        else -> emptyList()
    }

    val parsedWeights = parseGradientWeights(weightToken)
    val weights = when {
        parsedWeights.size == componentIds.size && componentIds.size in 2..4 -> parsedWeights
        componentIds.size == 2 && mixPercent != null -> listOf(100 - mixPercent, mixPercent)
        else -> emptyList()
    }

    val distributionMode = modeToken?.substring(1)?.toIntOrNull()
    val topMixMode = topToken?.substring(1)?.toIntOrNull()
    val fineTopLines = parseBooleanToken(fineToken)
    val ironingGlaze = parseBooleanToken(glazeToken)
    val warning = if (componentIds.isEmpty()) "Could not parse components" else null

    return ImportedMixRecipeRowSummary(
        rawRow = row,
        componentIds = componentIds,
        weights = weights,
        distributionMode = distributionMode,
        topMixMode = topMixMode,
        fineTopLines = fineTopLines,
        ironingGlaze = ironingGlaze,
        parseWarning = warning,
    )
}

private fun parseGradientIds(token: String?): List<Int> {
    val raw = token?.removePrefix("g").orEmpty().trim()
    if (raw.isEmpty()) return emptyList()
    return if (raw.contains('/')) {
        raw.split('/').mapNotNull { it.trim().toIntOrNull() }
    } else {
        raw.mapNotNull { it.digitToIntOrNull() }
    }
}

private fun parseGradientWeights(token: String?): List<Int> {
    val raw = token?.removePrefix("w").orEmpty().trim()
    if (raw.isEmpty()) return emptyList()
    return raw.split('/').mapNotNull { it.trim().toIntOrNull() }
}

private fun parseBooleanToken(token: String?): Boolean {
    val raw = token?.drop(1)?.trim().orEmpty()
    return raw == "1" || raw.equals("true", ignoreCase = true)
}

private fun rawMixPercentFromRow(row: String): Int {
    val tokens = row.split(',').map { it.trim() }
    return tokens.getOrNull(4)?.toIntOrNull()?.coerceIn(0, 100) ?: 50
}
