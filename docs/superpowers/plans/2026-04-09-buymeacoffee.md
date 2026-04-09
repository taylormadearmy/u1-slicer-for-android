# Buy Me a Coffee UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add two tappable BMaC touch points — a subtitle link in every TopAppBar and an About section in Settings.

**Architecture:** URL constants in a new `AppUrls.kt`; `coreVersion` StateFlow removed from `SlicerViewModel`; both `PrepareScreen` and `PreviewScreen` TopAppBars updated; new About section appended to `SettingsScreen`.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, `Intent.ACTION_VIEW` for URL opening (matches existing project pattern)

---

## Files

| File | Action |
|---|---|
| `app/src/main/java/com/u1/slicer/AppUrls.kt` | **Create** — `BMAC_URL` and `GITHUB_URL` constants |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | **Modify** — remove `_coreVersion` / `coreVersion` StateFlow and init assignment |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | **Modify** — `PrepareScreen` and `PreviewScreen` TopAppBars: remove `coreVersion` collect, add tappable subtitle |
| `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` | **Modify** — append About `SettingsSection` after Backup & Restore |

---

### Task 1: Add URL constants

**Files:**
- Create: `app/src/main/java/com/u1/slicer/AppUrls.kt`

- [ ] **Step 1: Create the constants file**

```kotlin
package com.u1.slicer

const val BMAC_URL = "https://buymeacoffee.com/taylormadearmy"
const val GITHUB_URL = "https://github.com/taylormadearmy/u1-slicer-for-android"
```

- [ ] **Step 2: Commit**

```bash
git add app/src/main/java/com/u1/slicer/AppUrls.kt
git commit -m "feat: add BMAC_URL and GITHUB_URL constants"
```

---

### Task 2: Remove coreVersion from SlicerViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`

- [ ] **Step 1: Delete the `_coreVersion` field and its public StateFlow (lines ~160–161)**

Remove these two lines:
```kotlin
private val _coreVersion = MutableStateFlow("")
val coreVersion: StateFlow<String> = _coreVersion.asStateFlow()
```

- [ ] **Step 2: Delete the `_coreVersion` assignment in `init` (lines ~354–358)**

Remove these lines from the `init` block:
```kotlin
_coreVersion.value = if (NativeLibrary.isLoaded) {
    "Snapmaker Orca 2.2.4 (Android ARM64)"
} else {
    "Native library not available"
}
```

- [ ] **Step 3: Build to confirm no remaining references**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "error:|coreVersion"
```

Expected: no errors mentioning `coreVersion`. If there are other references the compiler will call them out — fix them before continuing.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt
git commit -m "refactor: remove unused coreVersion StateFlow"
```

---

### Task 3: Update PrepareScreen TopAppBar

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Remove the `coreVersion` state collection from `PrepareScreen`**

Near the top of `PrepareScreen` (around line 696), remove:
```kotlin
val coreVersion by viewModel.coreVersion.collectAsState()
```

- [ ] **Step 2: Add `context` to `PrepareScreen`**

Immediately after the remaining `val state by viewModel.state.collectAsState()` line, add:
```kotlin
val context = LocalContext.current
```

(`LocalContext` is already imported in `MainActivity.kt`.)

- [ ] **Step 3: Replace the coreVersion subtitle in the PrepareScreen TopAppBar (lines ~742–746)**

Replace:
```kotlin
Text(
    coreVersion,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary
)
```

With:
```kotlin
Text(
    "☕ Support development",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.clickable {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(BMAC_URL)
            )
        )
    }
)
```

(`Modifier.clickable` is already imported. `BMAC_URL` comes from `AppUrls.kt` in the same package — no import needed.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: replace coreVersion subtitle with BMaC link in PrepareScreen header"
```

---

### Task 4: Update PreviewScreen TopAppBar

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`

- [ ] **Step 1: Remove the `coreVersion` state collection from `PreviewScreen`**

Near the top of `PreviewScreen` (around line 1119), remove:
```kotlin
val coreVersion by viewModel.coreVersion.collectAsState()
```

- [ ] **Step 2: Add `context` to `PreviewScreen`**

Immediately below the remaining state collections at the top of `PreviewScreen`, add:
```kotlin
val context = LocalContext.current
```

- [ ] **Step 3: Replace the coreVersion subtitle in the PreviewScreen TopAppBar (lines ~1134–1138)**

Replace:
```kotlin
Text(
    coreVersion,
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary
)
```

With:
```kotlin
Text(
    "☕ Support development",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.clickable {
        context.startActivity(
            android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(BMAC_URL)
            )
        )
    }
)
```

- [ ] **Step 4: Build to confirm clean**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "error:"
```

Expected: no errors.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: replace coreVersion subtitle with BMaC link in PreviewScreen header"
```

---

### Task 5: Add About section to SettingsScreen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt`

- [ ] **Step 1: Add `BuildConfig` import and `context` to SettingsScreen**

At the top of `SettingsScreen.kt`, add this import (it's not there yet):
```kotlin
import com.u1.slicer.BuildConfig
```

Then near the top of the `SettingsScreen` composable function, add:
```kotlin
val context = LocalContext.current
```

alongside the existing state declarations. (`LocalContext` is already imported on line 22.)

- [ ] **Step 2: Append the About section after the closing `}` of the `SettingsSection("Backup & Restore")` block (after line ~553)**

Add this block inside the `LazyColumn` / `Column` that contains the other `SettingsSection` calls, immediately after the Backup & Restore section:

```kotlin
SettingsSection("About") {
    // Version row
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("Version", style = MaterialTheme.typography.bodyMedium)
        Text(
            BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    // GitHub row
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(GITHUB_URL)
                    )
                )
            },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("GitHub", style = MaterialTheme.typography.bodyMedium)
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = "Open GitHub",
            tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }

    // Buy Me a Coffee card
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                context.startActivity(
                    android.content.Intent(
                        android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(BMAC_URL)
                    )
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
            Text("☕", fontSize = 22.sp)
            Column {
                Text(
                    "Buy Me a Coffee",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    color = Color.Black
                )
                Text(
                    "buymeacoffee.com/taylormadearmy",
                    fontSize = 10.sp,
                    color = Color(0xFF555555)
                )
            }
        }
    }
}
```

`Color`, `FontWeight`, `RoundedCornerShape`, `Row`, `Column`, `Card`, `CardDefaults`, `Arrangement`, `Alignment`, `Icon`, `Icons.AutoMirrored.Filled.ArrowForward`, `Modifier.clickable`, `sp`, `dp`, and `Text` are all already imported in `SettingsScreen.kt`. `BMAC_URL` and `GITHUB_URL` are in the same package — no import needed. `BuildConfig` was added in Step 1.

- [ ] **Step 3: Build to confirm clean**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | grep -E "error:"
```

Expected: no errors.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt
git commit -m "feat: add About section with BMaC card to SettingsScreen"
```

---

### Task 6: Install and smoke test

- [ ] **Step 1: Install on device**

```bash
./gradlew installDebug --no-daemon
```

- [ ] **Step 2: Smoke test — header**
  - Open the app
  - Tap `"☕ Support development"` subtitle in the header on any tab
  - Expected: system browser opens `https://buymeacoffee.com/taylormadearmy`

- [ ] **Step 3: Smoke test — Settings**
  - Navigate to Settings tab → scroll to the bottom
  - Expected: "About" section visible with Version, GitHub row, and yellow BMaC card
  - Tap GitHub row → `https://github.com/taylormadearmy/u1-slicer-for-android` opens in browser
  - Tap BMaC card → `https://buymeacoffee.com/taylormadearmy` opens in browser

- [ ] **Step 4: Run unit tests to confirm no regressions**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` — 725 tests, 0 failures.
