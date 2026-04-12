# F70 Check for Updates — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a "Check for Updates" button in the Settings About section that queries the GitHub Releases API and tells the user whether a newer version is available, with a link to download it.

**Architecture:** A small `UpdateChecker` utility class makes a single GET to the GitHub Releases API (`/releases/latest`), parses the `tag_name`, and compares it to `BuildConfig.VERSION_NAME`. The Settings composable calls this via a coroutine scope and shows the result inline (not a dialog — keeps it simple).

**Tech Stack:** OkHttp (already in project), Kotlin coroutines, Jetpack Compose, `org.json` (Android built-in)

---

## File Structure

| File | Action | Responsibility |
|------|--------|----------------|
| `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt` | Create | Fetch latest release from GitHub API, parse version, compare |
| `app/src/main/java/com/u1/slicer/AppUrls.kt` | Modify | Add `GITHUB_RELEASES_LATEST_URL` constant |
| `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` | Modify | Add "Check for Updates" row in the About section |
| `app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt` | Create | Unit tests for version comparison and JSON parsing |

---

### Task 1: Add the GitHub API URL constant

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/AppUrls.kt`

- [ ] **Step 1: Add the constant**

```kotlin
const val GITHUB_RELEASES_LATEST_URL =
    "https://api.github.com/repos/taylormadearmy/u1-slicer-for-android/releases/latest"
```

Add this after the existing `GITHUB_URL` line in `AppUrls.kt`.

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/AppUrls.kt
git commit -m "feat: F70 add GitHub releases API URL constant"
```

---

### Task 2: Write UpdateChecker with TDD

**Files:**
- Create: `app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt`
- Create: `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt`

- [ ] **Step 1: Write failing tests for version parsing and comparison**

```kotlin
package com.u1.slicer.network

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckerTest {

    // --- parseLatestVersion: extracts version from GitHub API JSON ---

    @Test
    fun `parseLatestVersion extracts tag_name without v prefix`() {
        val json = """{"tag_name":"v1.5.49","assets":[{"browser_download_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/download/v1.5.49/u1-slicer-v1.5.49.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestVersion handles tag without v prefix`() {
        val json = """{"tag_name":"1.5.49","assets":[{"browser_download_url":"https://example.com/app.apk"}]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("1.5.49", result?.version)
    }

    @Test
    fun `parseLatestVersion returns null for malformed JSON`() {
        assertNull(UpdateChecker.parseLatestRelease("not json"))
    }

    @Test
    fun `parseLatestVersion returns null for missing tag_name`() {
        val json = """{"assets":[]}"""
        assertNull(UpdateChecker.parseLatestRelease(json))
    }

    @Test
    fun `parseLatestVersion extracts first APK download URL from assets`() {
        val json = """{"tag_name":"v1.5.49","assets":[
            {"name":"u1-slicer-v1.5.49.apk","browser_download_url":"https://github.com/download/u1-slicer-v1.5.49.apk"},
            {"name":"source.zip","browser_download_url":"https://github.com/download/source.zip"}
        ]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/download/u1-slicer-v1.5.49.apk", result?.downloadUrl)
    }

    @Test
    fun `parseLatestVersion falls back to release page when no APK asset`() {
        val json = """{"tag_name":"v1.5.49","html_url":"https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49","assets":[]}"""
        val result = UpdateChecker.parseLatestRelease(json)
        assertEquals("https://github.com/taylormadearmy/u1-slicer-for-android/releases/tag/v1.5.49", result?.downloadUrl)
    }

    // --- isNewer: semantic version comparison ---

    @Test
    fun `isNewer returns true when remote patch is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.5.49", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote minor is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns true when remote major is higher`() {
        assertTrue(UpdateChecker.isNewer(remote = "2.0.0", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when versions are equal`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.48", current = "1.5.48"))
    }

    @Test
    fun `isNewer returns false when current is newer`() {
        assertFalse(UpdateChecker.isNewer(remote = "1.5.47", current = "1.5.48"))
    }

    @Test
    fun `isNewer handles different segment counts gracefully`() {
        assertTrue(UpdateChecker.isNewer(remote = "1.6", current = "1.5.48"))
        assertFalse(UpdateChecker.isNewer(remote = "1.5", current = "1.5.48"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.UpdateCheckerTest" --no-daemon
```

Expected: compilation error — `UpdateChecker` doesn't exist yet.

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/u1/slicer/network/UpdateChecker.kt`:

```kotlin
package com.u1.slicer.network

import android.util.Log
import com.u1.slicer.GITHUB_RELEASES_LATEST_URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for a newer version of the app.
 */
object UpdateChecker {

    private const val TAG = "UpdateChecker"

    data class ReleaseInfo(val version: String, val downloadUrl: String)

    /**
     * Parses the GitHub `/releases/latest` JSON response.
     * Returns version (without "v" prefix) and the first .apk asset URL
     * (or the release page URL if no APK asset is found).
     */
    fun parseLatestRelease(json: String): ReleaseInfo? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name", "").ifEmpty { return null }
            val version = tagName.removePrefix("v")

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
                downloadUrl = obj.optString("html_url", "")
            }
            if (downloadUrl.isNullOrEmpty()) return null

            ReleaseInfo(version, downloadUrl)
        } catch (e: Exception) {
            Log.w(TAG, "parseLatestRelease failed: ${e.message}")
            null
        }
    }

    /**
     * Semantic version comparison: true if [remote] is strictly newer than [current].
     */
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

    /**
     * Fetches the latest release from GitHub and returns a [ReleaseInfo] if a newer
     * version is available, or null if the app is up-to-date (or the check failed).
     */
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
                val body = response.body?.string() ?: return@withContext Result.failure(
                    Exception("Empty response from GitHub")
                )
                response.close()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        Exception("GitHub API returned ${response.code}")
                    )
                }

                val release = parseLatestRelease(body)
                    ?: return@withContext Result.failure(Exception("Could not parse release"))

                if (isNewer(release.version, currentVersion)) {
                    Result.success(release)
                } else {
                    Result.success(null) // up to date
                }
            } catch (e: Exception) {
                Log.w(TAG, "checkForUpdate failed: ${e.message}")
                Result.failure(e)
            }
        }
}
```

- [ ] **Step 4: Run tests to verify they pass**

```bash
./gradlew testDebugUnitTest --tests "com.u1.slicer.network.UpdateCheckerTest" --no-daemon
```

Expected: all 12 tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/network/UpdateChecker.kt \
       app/src/test/java/com/u1/slicer/network/UpdateCheckerTest.kt
git commit -m "feat: F70 add UpdateChecker with version parsing + comparison"
```

---

### Task 3: Add "Check for Updates" row to Settings screen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt`

- [ ] **Step 1: Add state variables and the UI row**

At the top of the `SettingsScreen` composable body (after the existing `val context = LocalContext.current` on line 127), add:

```kotlin
val scope = rememberCoroutineScope()
var updateState by remember { mutableStateOf<UpdateCheckState>(UpdateCheckState.Idle) }
```

Add the sealed interface above the `SettingsScreen` function (or in the same file):

```kotlin
private sealed interface UpdateCheckState {
    data object Idle : UpdateCheckState
    data object Checking : UpdateCheckState
    data class Available(val version: String, val downloadUrl: String) : UpdateCheckState
    data object UpToDate : UpdateCheckState
    data class Error(val message: String) : UpdateCheckState
}
```

In the About section, between the GitHub row (line 161) and the Buy Me a Coffee card (line 163), add:

```kotlin
// Check for Updates row
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable(enabled = updateState !is UpdateCheckState.Checking) {
            updateState = UpdateCheckState.Checking
            scope.launch {
                val result = UpdateChecker.checkForUpdate(BuildConfig.VERSION_NAME)
                updateState = result.fold(
                    onSuccess = { release ->
                        if (release != null) UpdateCheckState.Available(release.version, release.downloadUrl)
                        else UpdateCheckState.UpToDate
                    },
                    onFailure = { UpdateCheckState.Error(it.message ?: "Check failed") }
                )
            }
        },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Text("Check for Updates", style = MaterialTheme.typography.bodyMedium)
    when (val state = updateState) {
        is UpdateCheckState.Idle -> Icon(
            Icons.Default.Refresh,
            contentDescription = "Check for updates",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        is UpdateCheckState.Checking -> CircularProgressIndicator(
            modifier = Modifier.size(20.dp),
            strokeWidth = 2.dp
        )
        is UpdateCheckState.UpToDate -> Text(
            "Up to date",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
        is UpdateCheckState.Available -> Text(
            "v${state.version} available",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary
        )
        is UpdateCheckState.Error -> Text(
            "Check failed",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

// Download row + Buy Me a Coffee nudge (only shown when update is available)
if (updateState is UpdateCheckState.Available) {
    val available = updateState as UpdateCheckState.Available
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(available.downloadUrl))
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "Download v${available.version}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Download update",
            tint = MaterialTheme.colorScheme.primary
        )
    }

    // Gentle BMAC nudge alongside the update notification
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(BMAC_URL))
                )
            },
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFDD00)),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("\u2615", fontSize = 22.sp)
            Column {
                Text(
                    "Enjoying the app?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Black
                )
                Text(
                    "Buy me a coffee to support development",
                    fontSize = 10.sp,
                    color = Color(0xFF555555)
                )
            }
        }
    }
}
```

Add required imports at the top of the file:

```kotlin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.rememberCoroutineScope
import com.u1.slicer.network.UpdateChecker
import kotlinx.coroutines.launch
```

- [ ] **Step 2: Build and verify no compilation errors**

```bash
./gradlew compileDebugKotlin --no-daemon
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Install and manually verify on device**

```bash
./gradlew installDebug --no-daemon
```

Open Settings → About section → tap "Check for Updates":
- Should show spinner briefly
- Then show "Up to date" (since the app is the latest release)
- No crash

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "feat: F70 add Check for Updates button to Settings About section"
```

---

### Task 4: Run full test suite and update docs

**Files:**
- Modify: `CLAUDE.md` (test counts if changed)

- [ ] **Step 1: Run full JVM unit tests**

```bash
./gradlew testDebugUnitTest --no-daemon
```

Expected: all tests pass (count should increase by the number of UpdateCheckerTest cases — 12 new tests).

- [ ] **Step 2: Run instrumented tests**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon
```

Expected: 162 pass, 0 fail.

- [ ] **Step 3: Update CLAUDE.md**

Update the unit test total and add the new test class to the test inventory:

```
- `network/UpdateCheckerTest.kt` (12) — GitHub release JSON parsing, semantic version comparison, download URL extraction
```

Update the total unit test count (726 + 12 = 738).

- [ ] **Step 4: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: F70 update test counts for UpdateChecker"
```
