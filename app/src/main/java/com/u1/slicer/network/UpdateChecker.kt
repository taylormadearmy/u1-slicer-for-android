package com.u1.slicer.network

import android.util.Log
import com.u1.slicer.GITHUB_RELEASES_LATEST_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

object UpdateChecker {

    private const val TAG = "UpdateChecker"

    data class ReleaseInfo(val version: String, val downloadUrl: String, val releaseUrl: String)

    fun parseLatestRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").ifEmpty { return null }
            val version = tagName.removePrefix("v")
            val releaseUrl = obj.optString("html_url", "")

            val assets = obj.optJSONArray("assets")
            var downloadUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk")) {
                        downloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }
            if (downloadUrl.isNullOrEmpty()) {
                downloadUrl = releaseUrl
            }
            if (downloadUrl.isNullOrEmpty()) return null

            ReleaseInfo(version, downloadUrl, releaseUrl)
        } catch (e: Exception) {
            Log.w(TAG, "parseLatestRelease failed: ${e.message}")
            null
        }
    }

    fun isNewer(remote: String, current: String): Boolean {
        val r = remote.split(".").map { it.toIntOrNull() ?: 0 }
        val c = current.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(r.size, c.size)
        for (i in 0 until len) {
            val rv = r.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (rv > cv) return true
            if (rv < cv) return false
        }
        return false
    }

    suspend fun checkForUpdate(currentVersion: String): Result<ReleaseInfo?> =
        withContext(Dispatchers.IO) {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(10, TimeUnit.SECONDS)
                    .readTimeout(10, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder()
                    .url(GITHUB_RELEASES_LATEST_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val response = client.newCall(request).execute()
                val body = response.body?.string()
                val code = response.code
                response.close()

                if (code != 200 || body == null) {
                    return@withContext Result.failure(
                        Exception("GitHub API returned $code")
                    )
                }

                val release = parseLatestRelease(body)
                    ?: return@withContext Result.failure(Exception("Could not parse release"))

                if (isNewer(release.version, currentVersion)) {
                    Result.success(release)
                } else {
                    Result.success(null)
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkForUpdate failed: ${e.message}")
                Result.failure(e)
            }
        }
}
