package com.u1.slicer.data

/**
 * Derive a sanitised base filename (without extension) from the user-loaded
 * model name, falling back to the on-disk transient name when the model
 * name is unavailable.
 *
 * Used by F84 (Send to printer), F88 (Save + Share G-code) for consistent
 * naming.
 */
object ModelFileNaming {
    /**
     * Returns a sanitised base name (no extension, no special chars).
     * - `modelName = "Dragon Scale infinity.3mf"` → `"Dragon_Scale_infinity"`
     * - `modelName = null` or blank → strip extension from [fallbackOnDiskName]
     */
    fun baseName(modelName: String?, fallbackOnDiskName: String): String {
        val source = modelName?.takeIf { it.isNotBlank() }
            ?: fallbackOnDiskName.substringBeforeLast('.')
        return sanitise(source.substringBeforeLast('.'))
    }

    private fun sanitise(raw: String): String =
        raw.replace(Regex("[^A-Za-z0-9._-]+"), "_")
            .replace(Regex("_+"), "_")
            .trim('_')
            .ifBlank { "output" }
}
