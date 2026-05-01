package com.u1.slicer.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "slice_jobs")
data class SliceJob(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val modelName: String,
    val gcodePath: String,
    /** Absolute path to the durable copy of the source 3MF/STL in files/jobs/<id>/. Null for jobs
     *  saved before this column was added (schema v2). */
    val sourcePath: String? = null,
    val totalLayers: Int,
    val estimatedTimeSeconds: Float,
    val estimatedFilamentMm: Float,
    val estimatedFilamentGrams: Float = 0f,
    val layerHeight: Float,
    val fillDensity: Float,
    val nozzleTemp: Int,
    val bedTemp: Int,
    val supportEnabled: Boolean,
    val filamentType: String,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * Phase 2 (2026-04-28, schema v5) — canonical filament list size at
     * slice time. Lets `shareJobGcode()` reproduce the canonical→physical
     * mapping when sharing a historical job, instead of leaking raw
     * canonical-fileIndex G-code (T4-T9) to the printer-facing path. Null
     * for jobs saved before this column was added — those rows are pre-
     * Phase-2 and their stored G-code is already in physical-slot space,
     * so identity copy is correct.
     */
    val canonicalListSize: Int? = null,
    /**
     * Phase 2 (2026-04-28, schema v5) — confirmed user mapping at slice
     * time, encoded as a comma-separated string (e.g. `"0,1,2,3"`). Null
     * when the user hadn't gone through the Filament Mapping dialog
     * before slicing, OR for pre-schema-v5 jobs. Decode via
     * [colorMapping].
     */
    val colorMappingCsv: String? = null,
    /**
     * Phase 2 (2026-04-28, schema v6, post-round-2-review) — physical
     * slot picked for single-colour jobs at slice time. Single-colour
     * canonical lists (size == 1) intentionally have null
     * [colorMappingCsv]; the user's slot pick lives only in
     * `SlicerViewModel._selectedExtruder` until this column was added.
     * Reviewer 3's P1 finding: without persisting it,
     * `shareJobGcode` for a single-colour job sliced for E3 falls back
     * to identity mapping `[0]` and exports as T0/E1.
     *
     * Null for multi-colour jobs (the colorMappingCsv path covers
     * those) and for pre-schema-v6 jobs.
     */
    val selectedExtruderAtSlice: Int? = null,
)

/**
 * Phase 2 (2026-04-28) — decode a [SliceJob]'s `colorMappingCsv` into a
 * `List<Int>` (canonical fileIndex → physical slot). Returns null if the
 * CSV is null/blank/malformed.
 *
 * Top-level function (rather than extension property) so call sites that
 * have a `colorMapping` symbol in scope (e.g. `SlicerViewModel.colorMapping`
 * StateFlow) can reference it unambiguously without import gymnastics.
 */
fun decodedColorMapping(job: SliceJob): List<Int>? =
    job.colorMappingCsv
        ?.takeIf { it.isNotBlank() }
        ?.split(',')
        ?.mapNotNull { it.trim().toIntOrNull() }
        ?.takeIf { it.isNotEmpty() }
