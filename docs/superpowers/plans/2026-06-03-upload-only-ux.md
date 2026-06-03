# Upload-Only UX Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the misleading slot-picker on the send-to-hold path with an honest read-only upload confirmation sheet, and rename the button "Map & Upload" → "Upload Only".

**Architecture:** The Fix-A mapping change is already committed (canonical body for Upload-Only via `sendRemapForAction`). This plan is UI only: a new read-only `UploadConfirmationDialog`, branching the post-tap dialog on `PendingMappingSend.action`, and the button rename. Map & Print is untouched.

**Tech Stack:** Kotlin, Jetpack Compose, Material3. JVM unit tests (source-grep structural guard for Compose, per project convention).

**Spec:** `docs/superpowers/specs/2026-06-03-upload-only-ux-design.md`

---

### Task 1: `UploadConfirmationDialog` composable

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt` (add new composable at end of file; reuses the file-local `parseHexColor`)

- [ ] **Step 1: Add the composable**

Append to `FilamentMappingDialog.kt` (after the last function, before EOF):

```kotlin
/**
 * Upload-Only confirmation sheet (2026-06-03). The send-to-hold path ships
 * the CANONICAL G-code body and lets the printer's Filament Setup map it, so
 * there is no in-app slot picker here — this read-only sheet just confirms
 * the upload and tells the user the printer will ask for nozzle assignment.
 *
 * @param canonicalList  the file's canonical filament list (colour + material).
 * @param plateFileIndices  when plate-narrowed, the canonical fileIdx per row
 *   (for "Filament N" labels matching Prepare); null → positional.
 * @param modelName  shown as the sheet subtitle.
 */
@Composable
fun UploadConfirmationDialog(
    canonicalList: CanonicalFilamentList,
    plateFileIndices: List<Int>? = null,
    modelName: String? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    "Upload to printer",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                if (!modelName.isNullOrBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        modelName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))

                LazyColumn(
                    modifier = Modifier.heightIn(max = 320.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    itemsIndexed(canonicalList.filaments) { idx, entry ->
                        val displayFileIndex = plateFileIndices?.getOrNull(idx) ?: idx
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(28.dp)
                                    .clip(CircleShape)
                                    .background(parseHexColor(entry.color))
                                    .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    "Filament ${displayFileIndex + 1}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Text(
                                    entry.materialType ?: "—",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        "When you start this print on the printer, it will ask " +
                            "you to assign each colour to a nozzle.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(10.dp),
                    )
                }

                Spacer(Modifier.height(12.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = onConfirm) { Text("Upload") }
                }
            }
        }
    }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL (no unresolved `parseHexColor` / import errors).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt
git commit -m "feat: UploadConfirmationDialog read-only send-to-hold sheet"
```

---

### Task 2: Route Upload-Only to the new sheet + rename button

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` — Present-branch dialog (~L829-901), Upload-Only confirm handler, button label (~L2570)

- [ ] **Step 1: Branch the Present-path dialog on action**

In the `CanonicalLookup.Present` branch (the block that currently renders `FilamentMappingDialog` unconditionally at ~L829), wrap it:

```kotlin
when (pending.action) {
    PendingMappingSend.Action.UploadOnly -> {
        com.u1.slicer.ui.UploadConfirmationDialog(
            canonicalList = narrowedList,
            plateFileIndices = plateFileIndices,
            modelName = viewModel.modelFileName.value,
            onConfirm = {
                val sourceFile = java.io.File(pending.gcodePath)
                val heldFile = java.io.File(
                    sourceFile.parentFile,
                    "${sourceFile.nameWithoutExtension}.remapped.${sourceFile.extension}"
                )
                pendingMappingSend = null
                navigateTab(Routes.PRINTER)
                sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    LongOpService.start(toastContext, "Preparing G-code")
                    val physical = try {
                        com.u1.slicer.gcode.applyPrintTimeRemap(
                            source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                            output = com.u1.slicer.gcode.PhysicalGcodePath.of(heldFile),
                            // Canonical body — printer maps held files via Filament Setup.
                            colorMapping = com.u1.slicer.gcode.sendRemapForAction(
                                uploadOnly = true, physicalMapping = emptyList(),
                            ),
                        )
                    } finally {
                        LongOpService.stop(toastContext)
                    }
                    val modelName = viewModel.modelFileName.value
                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                        printerViewModel.sendUploadOnly(physical, modelName)
                    }
                }
            },
            onDismiss = { pendingMappingSend = null },
        )
    }
    PendingMappingSend.Action.PrintAndUpload -> {
        // existing FilamentMappingDialog(...) block, unchanged
    }
}
```

- [ ] **Step 2: Simplify the FilamentMappingDialog onConfirm to print-only**

Inside the (now PrintAndUpload-only) `FilamentMappingDialog` `onConfirm`, replace the `when (pending.action) { … }` send dispatch with a direct print call, and set the mapping explicitly to the physical remap:

```kotlin
val sendMapping = com.u1.slicer.gcode.sendRemapForAction(
    uploadOnly = false, physicalMapping = expanded,
)
val physical = try {
    com.u1.slicer.gcode.applyPrintTimeRemap(
        source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
        output = com.u1.slicer.gcode.PhysicalGcodePath.of(remappedFile),
        colorMapping = sendMapping,
    )
} finally {
    LongOpService.stop(toastContext)
}
val modelName = viewModel.modelFileName.value
withContext(kotlinx.coroutines.Dispatchers.Main) {
    printerViewModel.sendAndPrint(physical, modelName)
}
```

- [ ] **Step 3: Rename the button** (`MainActivity.kt` ~L2570)

```kotlin
Text(
    "Upload Only",
    fontWeight = FontWeight.Bold,
    fontSize = 13.sp,
    maxLines = 1,
    softWrap = false,
)
```

- [ ] **Step 4: Build**

Run: `./gradlew assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: route Upload Only to confirmation sheet; rename button"
```

---

### Task 3: Structural guard test

**Files:**
- Test: `app/src/test/java/com/u1/slicer/ui/UploadOnlyUxTest.kt` (create)

- [ ] **Step 1: Write the failing test**

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Upload-Only UX (2026-06-03). Structural guard — the project has no Compose
 * UI test harness. Asserts the send-to-hold path routes to the read-only
 * UploadConfirmationDialog (not the slot picker) and the button is renamed.
 */
class UploadOnlyUxTest {

    private fun source(rel: String): String {
        val f = listOf(File(rel), File("../$rel")).firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test
    fun uploadConfirmationDialog_exists() {
        val src = source("app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt")
        assertTrue(
            "UploadConfirmationDialog composable must exist",
            src.contains("fun UploadConfirmationDialog(")
        )
    }

    @Test
    fun uploadOnlyAction_routesToConfirmationDialog() {
        val src = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        assertTrue(
            "Upload Only must render UploadConfirmationDialog",
            src.contains("UploadConfirmationDialog(")
        )
    }

    @Test
    fun outlinedSendButton_renamedToUploadOnly() {
        val src = source("app/src/main/java/com/u1/slicer/MainActivity.kt")
        assertTrue("Button must read \"Upload Only\"", src.contains("\"Upload Only\""))
        assertFalse(
            "Stale \"Map & Upload\" label must be gone",
            src.contains("\"Map & Upload\"")
        )
    }
}
```

- [ ] **Step 2: Run to verify it passes** (code from Tasks 1-2 already in place)

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.ui.UploadOnlyUxTest" --no-daemon`
Expected: PASS (3 tests).

- [ ] **Step 3: Full unit suite**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/test/java/com/u1/slicer/ui/UploadOnlyUxTest.kt
git commit -m "test: structural guard for Upload Only routing + button rename"
```

---

### Task 4: Build APK for on-device test

- [ ] **Step 1: Build + stage APK**

```bash
./gradlew assembleDebug --no-daemon
cp app/build/outputs/apk/debug/app-debug.apk "/g/My Drive/claude/u1-slicer-internal-memory-nozzle-fix-debug.apk"
```

Expected: APK ~41 MB at the G-drive path (overwrites the earlier prototype build).
