package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 2 cascade-detector tests for [resolvePerFilamentTypeAndTemp].
 *
 * The bug class these tests defend against is a per-slot preset round-trip
 * for per-filament overrides: applying override at fileIdx N and reading
 * back through `presets[colorMapping[N]]` causes the override to leak into
 * every other filament that shares a slot. This was bug 4 in the Phase 2
 * smoke-test handoff (see
 * `docs/superpowers/specs/2026-04-26-phase2-architecture-review-handoff.md` §1).
 *
 * Each test asserts the override appears at the overridden index AND
 * **only** at that index — the cascade-free invariant.
 */
class PerFilamentResolverTest {

    private fun pla7(): CanonicalFilamentList = CanonicalFilamentList(
        filaments = (0 until 7).map { i ->
            FilamentEntry(
                fileIndex = i,
                color = "#%06X".format(0xFF0000 + i * 0x000010),
                materialType = "PLA",
                source = FilamentSource.FILE_COLOUR,
            )
        }
    )

    private fun fourPLAPresets(): List<ExtruderPreset> = (0..3).map { i ->
        ExtruderPreset(index = i, color = "#FF0000", materialType = "PLA")
    }

    /**
     * H2C benchy shape: 7 file filaments, 4 physical slots; collisions
     * (filament 0 and 4 both map to slot 0; filament 1 and 5 to slot 1; etc.).
     * The cascade bug would have made fileIdx 4 also become PETG when
     * fileIdx 0 was overridden because they share slot 0.
     */
    private fun h2cMapping(): List<Int> = listOf(0, 1, 2, 3, 0, 1, 2)

    @Test
    fun overrideAtIndexZero_PETG_appearsOnlyAtIndexZero() {
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = pla7(),
            overrides = mapOf(0 to (null to "PETG")),
            colorMapping = h2cMapping(),
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PETG", "PLA", "PLA", "PLA", "PLA", "PLA", "PLA"), types)
        assertEquals(listOf(235, 220, 220, 220, 220, 220, 220), temps)
    }

    @Test
    fun overrideAtIndexFour_PETG_appearsOnlyAtIndexFour() {
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = pla7(),
            overrides = mapOf(4 to (null to "PETG")),
            colorMapping = h2cMapping(),
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PLA", "PLA", "PLA", "PLA", "PETG", "PLA", "PLA"), types)
        assertEquals(listOf(220, 220, 220, 220, 235, 220, 220), temps)
    }

    @Test
    fun overrideAtIndexFive_PETG_appearsOnlyAtIndexFive() {
        // Specifically the case the handoff §1 bug 13 surfaces:
        // overrides at fileIndex >= 4 used to be silently dropped because
        // `MutableList(extCount)` capped the array at 4.
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = pla7(),
            overrides = mapOf(5 to (null to "PETG")),
            colorMapping = h2cMapping(),
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PLA", "PLA", "PLA", "PLA", "PLA", "PETG", "PLA"), types)
        assertEquals(listOf(220, 220, 220, 220, 220, 235, 220), temps)
    }

    @Test
    fun multipleOverrides_eachAppearsOnlyAtItsOwnIndex() {
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = pla7(),
            overrides = mapOf(
                0 to (null to "PETG"),  // shares slot 0 with fileIdx 4
                3 to (null to "ABS"),   // unique slot
                6 to (null to "TPU"),   // shares slot 2 with fileIdx 2
            ),
            colorMapping = h2cMapping(),
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PETG", "PLA", "PLA", "ABS", "PLA", "PLA", "TPU"), types)
        assertEquals(listOf(235, 220, 220, 270, 220, 220, 225), temps)
    }

    @Test
    fun noOverrides_declaredMultiColour_injectiveMapping_fileMaterialWins() {
        // B128: a normal multi-colour 3MF that DECLARES per-filament materials.
        // The file's declared material is the authoritative default — it wins
        // over the auto-mapped slot's preset material (which is just whatever
        // spool the user happens to have configured in that physical slot).
        // This is the inverse of the pre-B128 contract; the Prepare row now
        // shows the file's material, and the slice matches it.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", "TPU", FilamentSource.FILE_COLOUR),
            )
        )
        // Presets deliberately differ from the file so a regression to
        // slot-preset authority is observable.
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FF0000", materialType = "PETG"),
            ExtruderPreset(index = 1, color = "#00FF00", materialType = "ABS"),
            ExtruderPreset(index = 2, color = "#0000FF", materialType = "PLA"),
        )
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 2),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PLA", "PETG", "TPU"), types)
        // Temp comes from the FILE material's default when file material wins
        // and the mapped slot's material differs (slot's tuned profile no longer
        // applies). PLA=220, PETG=235, TPU=225.
        assertEquals(listOf(220, 235, 225), temps)
    }

    @Test
    fun noOverrides_declaredFileMaterialNull_fallsBackToSlotPreset() {
        // B128 guard: when the file does NOT declare a material for a filament
        // (materialType == null), there is nothing to honour, so the mapped
        // slot's preset material still supplies the default.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#0000FF", null, FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FF0000", materialType = "ABS"),
            ExtruderPreset(index = 1, color = "#0000FF", materialType = "PLA"),
        )
        val (types, _) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        // fileIdx 0 declares PETG -> file wins. fileIdx 1 declares null -> slot 1 preset (PLA).
        assertEquals(listOf("PETG", "PLA"), types)
    }

    @Test
    fun noOverrides_declaredFileMaterialMatchesSlot_keepsLinkedProfileTemp() {
        // B128: when the file's declared material equals the mapped slot's
        // material, the slot's tuned filament-profile temp is still applicable
        // and must survive (we only drop the profile temp when the resolved
        // material differs from the slot's material).
        val library = listOf(
            FilamentProfile(
                id = 42, name = "Custom PLA", material = "PLA",
                nozzleTemp = 215, bedTemp = 60, retractLength = 0.8f, retractSpeed = 45f,
            )
        )
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PETG", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PLA", filamentProfileId = 42),
            ExtruderPreset(index = 1, materialType = "PETG"),
        )
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1),
            presets = presets,
            filamentLibrary = library,
        )
        assertEquals(listOf("PLA", "PETG"), types)
        // slot 0 material (PLA) == file material (PLA) -> linked profile 215 survives.
        assertEquals(listOf(215, 235), temps)
    }

    @Test
    fun noOverrides_declaredButColourCollision_slotWinsForCollidingFilaments() {
        // B128 guard: file-material authority requires an INJECTIVE mapping —
        // a filament that shares a physical slot with another is a fold case,
        // so the physical slot's material is authoritative for the colliding
        // filaments. Only the filament with its own slot honours the file.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", "TPU", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "ABS"),
            ExtruderPreset(index = 1, materialType = "ABS"),
        )
        val (types, _) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 1), // fileIdx 1 and 2 collide on slot 1
            presets = presets,
            filamentLibrary = emptyList(),
        )
        // fileIdx 0 -> own slot 0 -> file PLA. fileIdx 1,2 -> shared slot 1 -> slot ABS.
        assertEquals(listOf("PLA", "ABS", "ABS"), types)
    }

    @Test
    fun noOverrides_paintFoldFile_slotWinsEvenWhenInjective() {
        // B128 guard: paint-segmentation files (non-empty paintStateMap, e.g.
        // SEMM/H2C) keep mapped-slot authority even with an injective mapping,
        // because their "filaments" are paint states, not declared spools.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
            ),
            paintStateMap = mapOf(1 to 0, 2 to 1),
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PETG"),
            ExtruderPreset(index = 1, materialType = "ABS"),
        )
        val (types, _) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PETG", "ABS"), types)
    }

    @Test
    fun noOverrides_singleColourDeclared_keepsSlotPresetMaterial_b118guard() {
        // B118 must not regress: a single-colour 3MF declaring PLA with E1 set
        // to PETG still slices as the loaded spool (PETG). File-material
        // authority is scoped to MULTI-colour files.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PETG"),
        )
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        assertEquals(listOf("PETG"), types)
        assertEquals(listOf(235), temps)
    }

    @Test
    fun h2cSupportFilamentThree_usesMappedSlotPetgEvenWhenFileDeclaresPla() {
        val canonical = CanonicalFilamentList(
            filaments = (0 until 7).map { i ->
                FilamentEntry(
                    fileIndex = i,
                    color = "#%06X".format(0x0086D6 + i),
                    materialType = "PLA",
                    source = FilamentSource.FILE_COLOUR,
                )
            }
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#0086D6", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFF00", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PETG"),
            ExtruderPreset(index = 3, color = "#6A00D5", materialType = "PLA"),
        )

        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 2, 3, 0, 1, 2),
            presets = presets,
            filamentLibrary = emptyList(),
        )

        assertEquals("PETG", types[2])
        assertEquals(235, temps[2])
    }

    @Test
    fun noOverrides_linkedFilamentProfileTemp_winsOverMaterialDefault() {
        val library = listOf(
            FilamentProfile(
                id = 42,
                name = "Custom PLA",
                material = "PLA",
                nozzleTemp = 215,  // user-tuned, deliberately not the 220 default
                bedTemp = 60,
                retractLength = 0.8f,
                retractSpeed = 45f,
            )
        )
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", null, FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PLA", filamentProfileId = 42)
        )
        val (_, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0),
            presets = presets,
            filamentLibrary = library,
        )
        assertEquals(listOf(215), temps)
    }

    @Test
    fun overrideMaterial_bypassesLinkedFilamentProfileTemp() {
        // The linked profile is for PLA at 215°. User overrides fileIdx 0
        // to PETG. Expected: PETG default (235°), NOT the 215° from the
        // PLA-tuned profile. This is the "PLA-tuned profile temp survives a
        // PETG override" bug guard.
        val library = listOf(
            FilamentProfile(
                id = 42,
                name = "Custom PLA",
                material = "PLA",
                nozzleTemp = 215,
                bedTemp = 60,
                retractLength = 0.8f,
                retractSpeed = 45f,
            )
        )
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, materialType = "PLA", filamentProfileId = 42)
        )
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = mapOf(0 to (null to "PETG")),
            colorMapping = listOf(0),
            presets = presets,
            filamentLibrary = library,
        )
        assertEquals(listOf("PETG"), types)
        assertEquals(listOf(235), temps)
    }

    @Test
    fun nullColorMapping_defaultsEveryFilamentToSlotZero() {
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = pla7(),
            overrides = mapOf(2 to (null to "PETG")),
            colorMapping = null,
            presets = fourPLAPresets(),
            filamentLibrary = emptyList(),
        )
        // Override still hits its own index even when no mapping exists.
        assertEquals(listOf("PLA", "PLA", "PETG", "PLA", "PLA", "PLA", "PLA"), types)
        assertEquals(listOf(220, 220, 235, 220, 220, 220, 220), temps)
    }

    @Test
    fun dc15_threeColourDeclaredFile_allFilamentsShowFileMaterial() {
        // DC15 regression guard (Discord 1510408385571586212, 2026-05-30): a 3-colour
        // 3MF that declares per-filament materials must show ALL THREE file-declared
        // materials on the Prepare row — not just the first one with the others
        // showing "none". B128's declared-context+injective-mapping guard should
        // honour all three.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", "TPU", FilamentSource.FILE_COLOUR),
            )
        )
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = "PLA"),
        )
        val (types, temps) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 2),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        // File-declared materials must all appear — no "PLA" leak from slot
        // presets to any of the three rows.
        assertEquals(
            "DC15: all three declared materials must surface on Prepare",
            listOf("PETG", "PLA", "TPU"),
            types,
        )
        // Temps follow the resolved material: PETG=235, PLA=220, TPU=225.
        // For PLA (idx 1), the slot preset material matches → preset temp used.
        assertEquals(listOf(235, 220, 225), temps)
    }

    @Test
    fun dc15_blankSlotMaterialDoesNotLeakAsNoneLabel() {
        // DC15 regression guard sibling: a corrupted/legacy ExtruderPreset JSON
        // that parsed `materialType` as "" must NOT show "" on Prepare. The
        // resolver must treat blank as null and fall through to the file's
        // declared material or "PLA" guarantee.
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PETG", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", null,    FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", null,    FilamentSource.FILE_COLOUR),
            )
        )
        // Filaments 2 and 3 have null material; slot presets at those indices
        // have BLANK materialType (the corrupted-JSON pathology).
        val presets = listOf(
            ExtruderPreset(index = 0, color = "#FFFFFF", materialType = "PLA"),
            ExtruderPreset(index = 1, color = "#FFFFFF", materialType = ""),
            ExtruderPreset(index = 2, color = "#FFFFFF", materialType = ""),
        )
        val (types, _) = resolvePerFilamentTypeAndTemp(
            canonical = canonical,
            overrides = emptyMap(),
            colorMapping = listOf(0, 1, 2),
            presets = presets,
            filamentLibrary = emptyList(),
        )
        // All three rows must be real material names — no blank or "none" leak.
        assertEquals(
            "DC15: blank slot materialType must not render as empty/none — " +
                "resolver must fall through to file material or PLA.",
            listOf("PETG", "PLA", "PLA"),
            types,
        )
        assertTrue(
            "No row may be blank, even with corrupted preset materialType",
            types.all { it.isNotBlank() },
        )
    }
}
