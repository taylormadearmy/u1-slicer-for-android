package com.u1.slicer.machine

import java.nio.file.Files
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class CachedOrcaProfileLookupTest {
    @Test
    fun `real Ender chain downloads once then resolves after offline restart`() {
        withCache { cache ->
            val server = MockWebServer()
            server.dispatcher = fixtureDispatcher(
                mapOf(
                    "/resources/profiles/Creality.json" to fixture("Creality/index.json"),
                    "/resources/profiles/Creality/machine/Creality%20Ender-3%20V3%20SE%200.4%20nozzle.json" to
                        fixture("Creality/machine/Creality Ender-3 V3 SE 0.4 nozzle.json"),
                    "/resources/profiles/Creality/machine/fdm_creality_common.json" to
                        fixture("Creality/machine/fdm_creality_common.json"),
                    "/resources/profiles/Creality/machine/fdm_machine_common.json" to
                        fixture("Creality/machine/fdm_machine_common.json"),
                )
            )
            server.start()
            val client = VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/"))
            val resolved = CachedOrcaProfileLookup(cache, client).resolveByName(
                vendor = "Creality",
                type = OrcaProfileType.MACHINE,
                name = "Creality Ender-3 V3 SE 0.4 nozzle",
            )
            assertEnderChain(resolved)
            assertEquals(4, server.requestCount)
            server.shutdown()

            val offline = CachedOrcaProfileLookup(cache, client).resolveByName(
                vendor = "Creality",
                type = OrcaProfileType.MACHINE,
                name = "Creality Ender-3 V3 SE 0.4 nozzle",
            )
            assertEnderChain(offline)
            assertEquals(4, server.requestCount)
        }
    }

    @Test
    fun `cross-vendor BBL parent is loaded from inferred vendor`() {
        withCache { cache ->
            val childName = "Imported PLA"
            val parentName = "Bambu PLA Basic @BBL X1C"
            cache.replaceVendor(
                index("Imported", filaments = listOf(VendorCatalogEntry(childName, "filament/child.json"))),
                mapOf("filament/child.json" to """{"type":"filament","name":"$childName","inherits":"$parentName","child":"yes"}"""),
            )
            cache.replaceVendor(
                index("BBL", filaments = listOf(VendorCatalogEntry(parentName, "filament/parent.json"))),
                mapOf("filament/parent.json" to """{"type":"filament","name":"$parentName","parent":"yes"}"""),
            )

            val resolved = CachedOrcaProfileLookup(cache, VendorCatalogClient()).resolveByName(
                vendor = "Imported",
                type = OrcaProfileType.FILAMENT,
                name = childName,
            )

            assertNotNull(resolved)
            val json = JSONObject(resolved!!)
            assertEquals("yes", json.getString("child"))
            assertEquals("yes", json.getString("parent"))
            assertFalse(json.has("inherits"))
        }
    }

    @Test
    fun `mobile vendor inference compatibility rules are preserved`() {
        assertEquals("BBL", CachedOrcaProfileLookup.inferVendor("Bambu PLA @BBL X1C", OrcaProfileType.FILAMENT))
        assertEquals("Creality", CachedOrcaProfileLookup.inferVendor("0.20mm Standard @Creality K1", OrcaProfileType.PROCESS))
        assertEquals("BBL", CachedOrcaProfileLookup.inferVendor("Bambu Lab P1S 0.4 nozzle", OrcaProfileType.MACHINE))
        assertEquals("BBL", CachedOrcaProfileLookup.inferVendor("Generic PLA", OrcaProfileType.FILAMENT))
        assertEquals("Prusa", CachedOrcaProfileLookup.inferVendor("Prusa PLA", OrcaProfileType.FILAMENT))
        assertNull(CachedOrcaProfileLookup.inferVendor("fdm_machine_common", OrcaProfileType.MACHINE))
    }

    @Test
    fun `missing preset name refreshes only the index then returns null`() {
        withCache { cache ->
            cache.replaceVendor(index("Acme"), emptyMap())
            val server = MockWebServer()
            server.enqueue(MockResponse().setResponseCode(404))
            server.start()
            try {
                assertNull(
                    CachedOrcaProfileLookup(
                        cache,
                        VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/")),
                    ).resolveByName("Acme", OrcaProfileType.MACHINE, "missing")
                )
                assertEquals("/resources/profiles/Acme.json", server.takeRequest().path)
                assertEquals(1, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    @Test
    fun `cached index miss refreshes the index before giving up`() {
        withCache { cache ->
            cache.replaceVendor(index("Acme"), emptyMap())
            val updatedIndex = """{
                "name":"Acme","version":"2","machine_model_list":[],
                "machine_list":[{"name":"New machine","sub_path":"machine/New machine.json"}]
            }""".trimIndent()
            val server = MockWebServer()
            server.dispatcher = fixtureDispatcher(
                mapOf(
                    "/resources/profiles/Acme.json" to updatedIndex,
                    "/resources/profiles/Acme/machine/New%20machine.json" to
                        """{"type":"machine","name":"New machine","new_key":"fetched"}""",
                )
            )
            server.start()
            try {
                val resolved = CachedOrcaProfileLookup(
                    cache,
                    VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/")),
                ).resolveByName("Acme", OrcaProfileType.MACHINE, "New machine")

                assertEquals("fetched", JSONObject(resolved!!).getString("new_key"))
                assertEquals(2, server.requestCount)
            } finally {
                server.shutdown()
            }
        }
    }

    private fun assertEnderChain(resolved: String?) {
        assertNotNull(resolved)
        val json = JSONObject(resolved!!)
        assertEquals("marlin2", json.getString("gcode_flavor"))
        assertEquals("1.2", json.getJSONArray("retraction_length").getString(0))
        assertEquals("0.4", json.getJSONArray("z_hop").getString(0))
        assertEquals("#FCE94F", json.getJSONArray("extruder_colour").getString(0))
        assertFalse(json.has("inherits"))
    }

    private fun index(
        vendor: String,
        machines: List<VendorCatalogEntry> = emptyList(),
        processes: List<VendorCatalogEntry> = emptyList(),
        filaments: List<VendorCatalogEntry> = emptyList(),
    ) = VendorCatalogIndex(
        name = vendor,
        version = "1",
        machineModelList = emptyList(),
        machineList = machines,
        processList = processes,
        filamentList = filaments,
    )

    private fun fixtureDispatcher(fixtures: Map<String, String>) = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse =
            fixtures[request.path?.substringBefore('?')]
                ?.let { MockResponse().setResponseCode(200).setBody(it) }
                ?: MockResponse().setResponseCode(404)
    }

    private fun fixture(path: String): String = requireNotNull(
        javaClass.classLoader?.getResource("machine/catalogue/$path")
    ).readText()

    private fun withCache(block: (ProfileCache) -> Unit) {
        val root = Files.createTempDirectory("profile-lookup-").toFile()
        try {
            block(ProfileCache(root))
        } finally {
            root.deleteRecursively()
        }
    }
}
