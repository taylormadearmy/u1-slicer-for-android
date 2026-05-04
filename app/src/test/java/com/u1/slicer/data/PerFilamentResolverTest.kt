package com.u1.slicer.data

import org.junit.Assert.assertEquals
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
    fun noOverrides_mappedSlotMaterials_matchPrepareRows() {
        val canonical = CanonicalFilamentList(
            filaments = listOf(
                FilamentEntry(0, "#FF0000", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(1, "#00FF00", "PLA", FilamentSource.FILE_COLOUR),
                FilamentEntry(2, "#0000FF", null, FilamentSource.FILE_COLOUR),
            )
        )
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
        // fileIdx 0 -> PETG (from slot 0 preset), fileIdx 1 -> ABS (from slot 1 preset),
        // fileIdx 2 -> PLA (canonical null, falls back to slot 2 preset = PLA).
        assertEquals(listOf("PETG", "ABS", "PLA"), types)
        assertEquals(listOf(235, 270, 220), temps)
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
}
