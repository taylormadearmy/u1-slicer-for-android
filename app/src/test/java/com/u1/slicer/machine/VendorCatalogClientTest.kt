package com.u1.slicer.machine

import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VendorCatalogClientTest {
    private val servers = mutableListOf<MockWebServer>()

    @After fun tearDown() = servers.forEach { it.shutdown() }

    @Test
    fun `parses recorded Snapmaker index`() {
        val result = VendorCatalogClient.parseVendorIndex(fixture("Snapmaker.json"))
        assertEquals("Snapmaker", result.name)
        assertTrue(result.version.isNotBlank())
        assertEquals("02.02.00.04", result.version)
        assertTrue(result.machineModelList.any { it.name == "Snapmaker J1" && it.subPath == "machine/Snapmaker J1.json" })
        assertTrue(result.machineList.any { it.name == "fdm_common" && it.subPath == "machine/fdm_common.json" })
    }

    @Test
    fun `parses recorded small vendor index`() {
        val result = VendorCatalogClient.parseVendorIndex(fixture("Co Print.json"))
        assertEquals("Co Print", result.name)
        assertFalse(result.machineModelList.isEmpty())
        assertFalse(result.machineList.isEmpty())
    }

    @Test(expected = VendorCatalogClient.CatalogError.Malformed::class)
    fun `malformed index is controlled failure`() {
        VendorCatalogClient.parseVendorIndex("not json")
    }

    @Test
    fun `fetches index and encodes vendor path segment`() {
        val server = server(MockResponse().setResponseCode(200).setBody(fixture("Co Print.json")))
        val client = VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/"))
        val result = client.fetchVendorIndex("Co Print")
        assertEquals("Co Print", result.name)
        assertEquals("/resources/profiles/Co%20Print.json", server.takeRequest().path)
    }

    @Test(expected = VendorCatalogClient.CatalogError.Http::class)
    fun `404 is controlled failure`() {
        val server = server(MockResponse().setResponseCode(404))
        VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/")).fetchVendorIndex("Missing")
    }

    @Test(expected = VendorCatalogClient.CatalogError.Malformed::class)
    fun `malformed network body is controlled failure`() {
        val server = server(MockResponse().setResponseCode(200).setBody("{}"))
        VendorCatalogClient(OkHttpClient(), server.url("/resources/profiles/")).fetchVendorIndex("Broken")
    }

    @Test
    fun `vendor list is the 43 pinned machine indexes`() {
        assertEquals(43, VendorCatalogClient.VENDOR_NAMES.size)
        assertTrue(VendorCatalogClient.VENDOR_NAMES.contains("Flashforge"))
        assertFalse(VendorCatalogClient.VENDOR_NAMES.contains("blacklist"))
        assertFalse(VendorCatalogClient.VENDOR_NAMES.contains("OrcaFilamentLibrary"))
    }

    private fun server(response: MockResponse): MockWebServer = MockWebServer().also {
        it.enqueue(response); it.start(); servers += it
    }

    private fun fixture(name: String): String =
        requireNotNull(javaClass.classLoader?.getResource("machine/catalogue/$name")).readText()
}
