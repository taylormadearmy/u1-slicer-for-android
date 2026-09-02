package com.u1.slicer.machine

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

data class VendorCatalogEntry(val name: String, val subPath: String)

data class VendorCatalogIndex(
    val name: String,
    val version: String,
    val machineModelList: List<VendorCatalogEntry>,
    val machineList: List<VendorCatalogEntry>,
)

/** Fetches the machine catalogue from the pinned OrcaSlicer profile tree. */
class VendorCatalogClient(
    private val httpClient: OkHttpClient = OkHttpClient(),
    private val profilesBaseUrl: HttpUrl = DEFAULT_BASE_URL,
) {
    sealed class CatalogError(message: String) : Exception(message) {
        class Http(val code: Int) : CatalogError("Orca profile request failed: HTTP $code")
        class Malformed(message: String) : CatalogError(message)
        class Network(cause: Throwable) : CatalogError(cause.message ?: "Orca profile request failed")
    }

    fun fetchVendorIndex(vendor: String): VendorCatalogIndex {
        val url = profilesBaseUrl.newBuilder()
            .addPathSegment("$vendor.json")
            .build()
        val request = Request.Builder().url(url).get().build()
        return try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw CatalogError.Http(response.code)
                val body = response.body?.string() ?: throw CatalogError.Malformed("Empty vendor index")
                parseVendorIndex(body)
            }
        } catch (error: CatalogError) {
            throw error
        } catch (error: Exception) {
            throw CatalogError.Network(error)
        }
    }

    companion object {
        const val ORCA_PROFILE_TAG = "v2.2.0"
        private val DEFAULT_BASE_URL = "https://raw.githubusercontent.com/SoftFever/OrcaSlicer/$ORCA_PROFILE_TAG/resources/profiles/".toHttpUrl()

        // Generated from the pinned v2.2.0 tag; GitHub raw does not enumerate directories.
        val VENDOR_NAMES: List<String> = listOf(
            "Anker", "Anycubic", "Artillery", "BBL", "BIQU", "Blocks", "Chuanying",
            "Co Print", "Comgrow", "CONSTRUCT3D", "Creality", "Custom", "Dremel", "Elegoo",
            "FLSun", "Flashforge", "FlyingBear", "Folgertech", "Geeetech", "Ginger Additive", "InfiMech",
            "Kingroon", "MagicMaker", "Mellow", "OrcaArena", "Peopoly", "Positron3D", "Prusa",
            "Qidi", "Raise3D", "Ratrig", "RolohaunDesign", "SecKit", "Snapmaker", "Sovol",
            "Tronxy", "TwoTrees", "UltiMaker", "Vivedino", "Voron", "Voxelab", "Vzbot", "Wanhao",
        )

        fun parseVendorIndex(json: String): VendorCatalogIndex = try {
            val root = JSONObject(json)
            val name = root.optString("name").takeIf { it.isNotBlank() }
                ?: throw CatalogError.Malformed("Vendor index has no name")
            val version = root.optString("version").takeIf { it.isNotBlank() }
                ?: throw CatalogError.Malformed("Vendor index has no version")
            VendorCatalogIndex(
                name,
                version,
                parseEntries(root, "machine_model_list"),
                parseEntries(root, "machine_list"),
            )
        } catch (error: CatalogError) {
            throw error
        } catch (error: Exception) {
            throw CatalogError.Malformed("Malformed vendor index: ${error.message ?: "invalid JSON"}")
        }

        private fun parseEntries(root: JSONObject, key: String): List<VendorCatalogEntry> {
            val array = root.optJSONArray(key) ?: throw CatalogError.Malformed("Vendor index has no $key")
            return buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: throw CatalogError.Malformed("Invalid $key entry")
                    val name = item.optString("name").takeIf { it.isNotBlank() }
                        ?: throw CatalogError.Malformed("$key entry has no name")
                    val subPath = item.optString("sub_path").takeIf { it.isNotBlank() }
                        ?: throw CatalogError.Malformed("$key entry has no sub_path")
                    add(VendorCatalogEntry(name, subPath))
                }
            }
        }
    }
}
