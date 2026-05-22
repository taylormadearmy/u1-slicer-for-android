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
}
