package com.u1.slicer.data

import org.junit.Assert.*
import org.junit.Test

class PrintersRepositoryTest {

    @Test
    fun `applyAdd appends to list and does not change active`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        val newP = Printer(id = "b", nickname = "B", moonrakerUrl = "http://b")
        val next = PrintersRepository.applyAdd(initial, newP)
        assertEquals(listOf("a", "b"), next.printers.map { it.id })
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applyUpdate replaces entry by id`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A-old", moonrakerUrl = "http://old"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val updated = initial.printers[0].copy(nickname = "A-new", moonrakerUrl = "http://new")
        val next = PrintersRepository.applyUpdate(initial, updated)
        assertEquals("A-new", next.printers[0].nickname)
        assertEquals("http://new", next.printers[0].moonrakerUrl)
        assertEquals("B", next.printers[1].nickname)
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applyDelete rejects the active printer`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        try {
            PrintersRepository.applyDelete(initial, "a")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("active"))
        }
    }

    @Test
    fun `applyDelete rejects deleting the last printer`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        try {
            PrintersRepository.applyDelete(initial, "a")
            fail("expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("last") || e.message!!.contains("active"))
        }
    }

    @Test
    fun `applyDelete removes non-active and preserves active`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val next = PrintersRepository.applyDelete(initial, "b")
        assertEquals(listOf("a"), next.printers.map { it.id })
        assertEquals("a", next.activeId)
    }

    @Test
    fun `applySetActive switches active when id is known`() {
        val initial = PrintersConfig(
            printers = listOf(
                Printer(id = "a", nickname = "A", moonrakerUrl = "http://a"),
                Printer(id = "b", nickname = "B", moonrakerUrl = "http://b"),
            ),
            activeId = "a",
        )
        val next = PrintersRepository.applySetActive(initial, "b")
        assertEquals("b", next.activeId)
    }

    @Test
    fun `applySetActive is a no-op when id is unknown`() {
        val initial = PrintersConfig(
            printers = listOf(Printer(id = "a", nickname = "A", moonrakerUrl = "http://a")),
            activeId = "a",
        )
        val next = PrintersRepository.applySetActive(initial, "ghost")
        assertSame(initial, next)
    }

    // ---- Migration helpers (pure functions of legacy values) ----

    @Test
    fun `migration with legacy URL and presets produces single Printer 1 entry`() {
        val legacyUrl = "http://192.168.1.50"
        val legacyPresetsJson = serializeExtruderPresets(listOf(
            ExtruderPreset(index = 0, color = "#FFAA00", materialType = "PETG"),
            ExtruderPreset(index = 1, color = "#0000FF", materialType = "PLA"),
            ExtruderPreset(index = 2, color = "#00FF00", materialType = "PLA"),
            ExtruderPreset(index = 3, color = "#FFFFFF", materialType = "PLA"),
        ))
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = legacyUrl,
            legacyExtruderPresetsJson = legacyPresetsJson,
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals(legacyUrl, cfg.printers[0].moonrakerUrl)
        assertEquals("#FFAA00", cfg.printers[0].extruderPresets[0].color)
        assertEquals("fixed-uuid-1", cfg.printers[0].id)
        assertEquals("fixed-uuid-1", cfg.activeId)
    }

    @Test
    fun `migration with blank legacy URL produces entry with empty URL`() {
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = "",
            legacyExtruderPresetsJson = "",
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("", cfg.printers[0].moonrakerUrl)
        // defaultExtruderPresets() always returns 4 slots
        assertEquals(4, cfg.printers[0].extruderPresets.size)
    }

    @Test
    fun `migration with no legacy values still produces a valid Printer 1`() {
        val cfg = PrintersRepository.buildMigratedConfig(
            legacyUrl = null,
            legacyExtruderPresetsJson = null,
            idFactory = { "fixed-uuid-1" },
        )
        assertEquals(1, cfg.printers.size)
        assertEquals("Printer 1", cfg.printers[0].nickname)
        assertEquals("", cfg.printers[0].moonrakerUrl)
    }
}
