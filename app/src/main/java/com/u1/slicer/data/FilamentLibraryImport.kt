package com.u1.slicer.data

/** Shown on TD / refractive-index preview rows — these fields do not affect slicing. */
const val FUTURE_TRANSLUCENCY_NOTE = "For future translucency features — not used in slicing"

data class ImportPreviewRow(val label: String, val value: String, val note: String? = null)

/** True when the entry carries anything beyond colour + material worth importing. */
fun hasImportableData(e: FilamentLibraryEntry): Boolean =
    e.minNozzle != null || e.maxNozzle != null || e.minBed != null || e.maxBed != null ||
        e.density != null || e.td != null || e.ri != null

private fun rangeText(lo: Int?, hi: Int?): String? = when {
    lo != null && hi != null && lo != hi -> "$lo–$hi °C"
    lo != null -> "$lo °C"
    hi != null -> "$hi °C"
    else -> null
}

/** Field-by-field list of exactly what an import would bring in — present fields only. */
fun buildImportPreview(e: FilamentLibraryEntry): List<ImportPreviewRow> {
    val rows = mutableListOf<ImportPreviewRow>()
    rangeText(e.minNozzle, e.maxNozzle)?.let { rows.add(ImportPreviewRow("Nozzle temperature", it)) }
    rangeText(e.minBed, e.maxBed)?.let { rows.add(ImportPreviewRow("Bed temperature", it)) }
    e.density?.let { rows.add(ImportPreviewRow("Density", "$it g/cm³")) }
    e.td?.let { rows.add(ImportPreviewRow("Transmission distance", "$it", FUTURE_TRANSLUCENCY_NOTE)) }
    e.ri?.let { rows.add(ImportPreviewRow("Refractive index", "$it", FUTURE_TRANSLUCENCY_NOTE)) }
    return rows
}

private fun midpoint(lo: Int?, hi: Int?): Int? = when {
    lo != null && hi != null -> (lo + hi) / 2
    lo != null -> lo
    hi != null -> hi
    else -> null
}

private fun defaultNozzleFor(material: String): Int = when (material.uppercase()) {
    "PETG" -> 235
    "ABS", "ASA" -> 250
    else -> 220
}

private fun defaultBedFor(material: String): Int = when (material.uppercase()) {
    "PETG" -> 70
    "ABS", "ASA" -> 90
    "TPU" -> 50
    else -> 60
}

/**
 * Map a library entry to a [FilamentProfile]. Re-imports update the existing
 * profile in place (same id, same name) so no duplicates accumulate — lookup
 * is by exact profile name "<brand> <name>" (see FilamentDao.getByName).
 */
fun libraryEntryToProfile(e: FilamentLibraryEntry, existing: FilamentProfile?): FilamentProfile {
    val base = existing ?: FilamentProfile(
        name = e.displayName,
        material = e.material,
        nozzleTemp = defaultNozzleFor(e.material),
        bedTemp = defaultBedFor(e.material),
        retractLength = 0.8f,
        retractSpeed = 45f,
    )
    return base.copy(
        name = e.displayName,
        material = e.material,
        nozzleTemp = midpoint(e.minNozzle, e.maxNozzle) ?: base.nozzleTemp,
        bedTemp = midpoint(e.minBed, e.maxBed) ?: base.bedTemp,
        density = e.density?.toFloat() ?: base.density,
        color = e.hex ?: base.color,
    )
}

/**
 * Insert or update the [FilamentProfile] for a library entry (lookup by exact
 * profile name — see [FilamentDao.getByName]) and return its row id.
 */
suspend fun upsertLibraryProfile(dao: FilamentDao, entry: FilamentLibraryEntry): Long {
    val existing = dao.getByName(entry.displayName)
    val profile = libraryEntryToProfile(entry, existing)
    return if (existing != null) {
        dao.update(profile)
        existing.id
    } else {
        dao.insert(profile)
    }
}
