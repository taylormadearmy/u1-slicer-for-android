package com.u1.slicer.data

import com.u1.slicer.bambu.bambuCanonicalList
import com.u1.slicer.bambu.prusaSlicerCanonicalList
import java.io.File

/**
 * Phase 2.1 — top-level dispatcher that produces a [CanonicalFilamentList]
 * from any supported input file format.
 *
 * Detection order:
 *   1. Bambu / OrcaSlicer 3MF (Metadata/project_settings.config present).
 *   2. PrusaSlicer 3MF (Metadata/Slic3r_PE.config present).
 *   3. STL — synthesised single-entry list keyed to the user's selected
 *      extruder preset. Caller must supply the preset's colour and material
 *      type because STL geometry has no embedded filament data.
 *
 * Returns `null` for files we don't recognise. Callers should fall back to
 * the STL synthesiser if [stlCanonicalList] applies (the file is an STL),
 * or surface an error to the user otherwise.
 *
 * The 3MF detection is order-sensitive — Bambu's `project_settings.config`
 * doesn't appear in PrusaSlicer files and vice-versa, so each format's
 * normalizer self-rejects when the relevant config is absent.
 */
fun canonicalListAtLoad(file: File): CanonicalFilamentList? {
    if (!file.exists() || !file.isFile) return null
    val name = file.name.lowercase()

    if (name.endsWith(".3mf")) {
        bambuCanonicalList(file)?.let { return it }
        prusaSlicerCanonicalList(file)?.let { return it }
        return null
    }

    // STL handling is caller-driven (it needs the user's selected
    // extruder preset, which is a UI / ViewModel concern). The
    // dispatcher signals "no embedded filament data" by returning null
    // for non-3MF inputs; callers wrap the call with
    // `?: stlCanonicalList(presetColor, presetMaterialType)`.
    return null
}
