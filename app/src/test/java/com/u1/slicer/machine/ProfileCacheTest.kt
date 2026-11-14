package com.u1.slicer.machine

import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class ProfileCacheTest {
    @Test fun `first install and offline restart read exact index and profiles`() {
        withCache { cache, root ->
            val rawIndex = """{"name":"Acme","version":"1.2.3","machine_model_list":[],"machine_list":[]}"""
            cache.replaceVendor(
                VendorCatalogClient.parseVendorIndex(rawIndex),
                mapOf("machine/a.json" to "A", "process/p.json" to "P"),
            )
            val restarted = ProfileCache(root)
            val persistedIndex = restarted.readVendorIndex("Acme")
            assertEquals("1.2.3", persistedIndex?.version)
            assertEquals(rawIndex, persistedIndex?.rawJson)
            assertEquals("A", restarted.readProfile("Acme", "machine/a.json"))
            assertEquals("P", restarted.readProfile("Acme", "process/p.json"))
        }
    }

    @Test fun `dotted numeric versions compare correctly`() {
        assertTrue(ProfileCache.isNewerVersion("2.0.0", "1.99.99"))
        assertFalse(ProfileCache.isNewerVersion("1.2", "1.2.0"))
        assertFalse(ProfileCache.isNewerVersion("1.1.9", "1.2"))
    }

    @Test fun `OTA check compares remote version with installed vendor index`() {
        withCache { cache, _ ->
            assertTrue(cache.needsUpdate(index("1.0.0")))
            cache.replaceVendor(index("1.2.0"), emptyMap())
            assertFalse(cache.needsUpdate(index("1.1.9")))
            assertFalse(cache.needsUpdate(index("1.2")))
            assertTrue(cache.needsUpdate(index("1.2.1")))
        }
    }

    @Test fun `replacement removes old files and retains one backup`() {
        withCache { cache, root ->
            cache.replaceVendor(index("1"), mapOf("old.json" to "old"))
            cache.replaceVendor(index("2"), mapOf("new.json" to "new"))
            assertNull(cache.readProfile("Acme", "old.json"))
            assertEquals("new", cache.readProfile("Acme", "new.json"))
            assertEquals("1", ProfileCache(root).readBackupIndex("Acme")?.version)
            cache.replaceVendor(index("3"), mapOf("third.json" to "third"))
            assertEquals("2", ProfileCache(root).readBackupIndex("Acme")?.version)
        }
    }

    @Test fun `rollback restores prior version`() {
        withCache { cache, _ ->
            cache.replaceVendor(index("1"), mapOf("old.json" to "old"))
            cache.replaceVendor(index("2"), mapOf("new.json" to "new"))
            cache.rollback("Acme")
            assertEquals("1", cache.readVendorIndex("Acme")?.version)
            assertEquals("2", cache.readBackupIndex("Acme")?.version)
            assertEquals("old", cache.readProfile("Acme", "old.json"))
            assertNull(cache.readProfile("Acme", "new.json"))
        }
    }

    @Test fun `failed and unsafe replacement leave current intact`() {
        withCache { cache, _ ->
            cache.replaceVendor(index("1"), mapOf("old.json" to "old"))
            assertThrows(IllegalArgumentException::class.java) { cache.replaceVendor(index("2"), mapOf("../bad" to "bad")) }
            assertEquals("1", cache.readVendorIndex("Acme")?.version)
            assertEquals("old", cache.readProfile("Acme", "old.json"))
            assertThrows(IllegalArgumentException::class.java) { cache.replaceVendor(index("2"), mapOf("ok" to "ok"), vendor = "../bad") }
        }
    }

    @Test fun `offline restart recovers update interrupted between atomic moves`() {
        withCache { cache, root ->
            cache.replaceVendor(index("1"), mapOf("machine/a.json" to "old"))
            Files.move(root.resolve("Acme").toPath(), root.resolve(".Acme.backup").toPath())
            root.resolve(".Acme.staging-crash").mkdirs()

            val restarted = ProfileCache(root)

            assertEquals("1", restarted.readVendorIndex("Acme")?.version)
            assertEquals("old", restarted.readProfile("Acme", "machine/a.json"))
            assertFalse(root.resolve(".Acme.staging-crash").exists())
        }
    }

    private fun index(version: String) = VendorCatalogIndex("Acme", version, emptyList(), emptyList())
    private fun withCache(block: (ProfileCache, java.io.File) -> Unit) {
        val root = Files.createTempDirectory("profile-cache-").toFile()
        try { block(ProfileCache(root), root) } finally { root.deleteRecursively() }
    }
}
