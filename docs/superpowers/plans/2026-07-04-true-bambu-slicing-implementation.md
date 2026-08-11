# True First-Class Bambu Slicing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a true first-class Bambu slicing target, proven on A1 Mini, while preserving the stable Snapmaker/Moonraker path.

**Architecture:** Introduce a first-class slice target model and a typed slice artifact pipeline, then route the existing Snapmaker flow through that abstraction with no behavior change. Add a Bambu-specific machine/config resolver plus a Kotlin-side Bambu project writer that packages a Bambu-targeted slice into a `.3mf` artifact the existing Bambu LAN transport can upload and start.

**Tech Stack:** Kotlin, Jetpack Compose, DataStore, Room, JNI/C++ OrcaSlicer bridge, JVM unit tests, Android debug build validation on Pixel 8a and A1 Mini.

> Historical implementation plan. The completed v4.0.0 scope supports A1 Mini, A1, P1P, P1S, X1C, X1E and H2D, with physical print validation on A1 Mini and H2D. The A1-Mini-only wording below records the initial delivery plan.

---

## File Structure

### New files

- `app/src/main/java/com/u1/slicer/slice/SlicerTarget.kt`
  - First-class slice target ids, families, and printer compatibility rules.
- `app/src/main/java/com/u1/slicer/slice/SliceCapabilities.kt`
  - Per-target capability model and beta policy metadata.
- `app/src/main/java/com/u1/slicer/slice/SliceArtifact.kt`
  - Typed slice outputs (`MoonrakerGcodeArtifact`, `BambuProjectArtifact`).
- `app/src/main/java/com/u1/slicer/slice/SliceTargetResolver.kt`
  - Maps active printer + UI selection into the effective slice target.
- `app/src/main/java/com/u1/slicer/bambu/BambuMachineProfile.kt`
  - Machine constants for A1 Mini first, with generic shape for future Bambu models.
- `app/src/main/java/com/u1/slicer/bambu/BambuTargetedConfigResolver.kt`
  - Converts shared app intent into a Bambu-targeted `SliceConfig`.
- `app/src/main/java/com/u1/slicer/bambu/BambuProjectWriter.kt`
  - Writes a sendable Bambu `.3mf` project from a Bambu-targeted slice result.
- `app/src/test/java/com/u1/slicer/slice/SlicerTargetResolverTest.kt`
  - Foundation tests for target selection and printer compatibility.
- `app/src/test/java/com/u1/slicer/slice/SliceCapabilitiesTest.kt`
  - Capability and beta policy tests.
- `app/src/test/java/com/u1/slicer/bambu/BambuTargetedConfigResolverTest.kt`
  - A1 Mini config-resolution tests.
- `app/src/test/java/com/u1/slicer/bambu/BambuProjectWriterTest.kt`
  - Verifies generated `.3mf` entries and metadata.
- `app/src/test/java/com/u1/slicer/SliceArtifactRoutingTest.kt`
  - End-to-end JVM routing tests for Snapmaker vs Bambu artifact selection.

### Modified files

- `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
  - Owns selected slice target, resolves target-aware config, exposes latest typed slice artifact.
- `app/src/main/java/com/u1/slicer/MainActivity.kt`
  - Adds slice-target UI, beta messaging, and routes send actions through typed artifacts.
- `app/src/main/java/com/u1/slicer/data/SessionState.kt`
  - Persists selected slice target across process death.
- `app/src/main/java/com/u1/slicer/data/SettingsBackup.kt`
  - Includes selected slice target in backups.
- `app/src/main/java/com/u1/slicer/printer/BambuProjectFileInspector.kt`
  - Reused as a validator for generated Bambu project artifacts.
- `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`
  - Accepts the new Bambu slice artifact lane without changing stable Snapmaker send behavior.
- `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt`
  - Session-state round trip for target persistence.
- `app/src/test/java/com/u1/slicer/BambuBetaUiPolicyTest.kt`
  - Expanded to cover true-slice beta messaging.
- `app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt`
  - Structural guardrails for target picker and beta copy.

### Native files to leave untouched in the first proving slice

- `app/src/main/cpp/src/sapil_print.cpp`
- `app/src/main/cpp/src/slicer_wrapper.cpp`
- `app/src/main/java/com/u1/slicer/NativeLibrary.kt`

The first proving slice should keep JNI stable and package the Bambu artifact in Kotlin after a Bambu-targeted native G-code slice. If we later prove we need native `.3mf` export or richer plate metadata, that becomes a follow-up task after the A1 Mini vertical slice is working.

---

### Task 1: Introduce first-class slice targets

**Files:**
- Create: `app/src/main/java/com/u1/slicer/slice/SlicerTarget.kt`
- Create: `app/src/main/java/com/u1/slicer/slice/SliceTargetResolver.kt`
- Test: `app/src/test/java/com/u1/slicer/slice/SlicerTargetResolverTest.kt`

- [ ] **Step 1: Write the failing target-selection tests**

```kotlin
package com.u1.slicer.slice

import com.u1.slicer.data.BambuConfig
import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SlicerTargetResolverTest {

    @Test
    fun `moonraker printer defaults to snapmaker target`() {
        val printer = Printer(
            id = "p1",
            nickname = "U1",
            kind = PrinterKind.MOONRAKER,
            moonrakerUrl = "http://printer"
        )

        assertEquals(
            SlicerTarget.SnapmakerU1,
            resolveDefaultSliceTarget(activePrinter = printer),
        )
    }

    @Test
    fun `bambu printer defaults to matching bambu model target`() {
        val printer = Printer(
            id = "p2",
            nickname = "A1 Mini",
            kind = PrinterKind.BAMBU_LAN,
            bambu = BambuConfig("192.168.1.8", "12345678", "03W09C123400001", BambuModel.A1_MINI),
        )

        assertEquals(
            SlicerTarget.BambuA1Mini,
            resolveDefaultSliceTarget(activePrinter = printer),
        )
    }

    @Test
    fun `compatibility is strict across printer families`() {
        assertTrue(SlicerTarget.BambuA1Mini.isCompatibleWith(PrinterKind.BAMBU_LAN, BambuModel.A1_MINI))
        assertFalse(SlicerTarget.BambuA1Mini.isCompatibleWith(PrinterKind.MOONRAKER, null))
        assertFalse(SlicerTarget.SnapmakerU1.isCompatibleWith(PrinterKind.BAMBU_LAN, BambuModel.A1_MINI))
    }
}
```

- [ ] **Step 2: Run the new test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.slice.SlicerTargetResolverTest --no-daemon`
Expected: FAIL with unresolved references for `SlicerTarget`, `resolveDefaultSliceTarget`, or `isCompatibleWith`.

- [ ] **Step 3: Write the minimal target model and resolver**

```kotlin
package com.u1.slicer.slice

import com.u1.slicer.data.BambuModel
import com.u1.slicer.data.Printer
import com.u1.slicer.data.PrinterKind

enum class SliceTargetFamily {
    SNAPMAKER,
    BAMBU,
}

enum class SlicerTarget(
    val family: SliceTargetFamily,
    val beta: Boolean,
) {
    SnapmakerU1(
        family = SliceTargetFamily.SNAPMAKER,
        beta = false,
    ),
    BambuA1Mini(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuA1(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuP1S(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuP1P(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuX1C(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuX1E(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    ),
    BambuH2D(
        family = SliceTargetFamily.BAMBU,
        beta = true,
    );

    fun isCompatibleWith(kind: PrinterKind, bambuModel: BambuModel?): Boolean {
        return when (this) {
            SnapmakerU1 -> kind == PrinterKind.MOONRAKER
            BambuA1Mini -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.A1_MINI
            BambuA1 -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.A1
            BambuP1S -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.P1S
            BambuP1P -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.P1P
            BambuX1C -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.X1C
            BambuX1E -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.X1E
            BambuH2D -> kind == PrinterKind.BAMBU_LAN && bambuModel == BambuModel.H2D
        }
    }
}

fun resolveDefaultSliceTarget(activePrinter: Printer?): SlicerTarget =
    when (activePrinter?.kind) {
        PrinterKind.BAMBU_LAN -> when (activePrinter.bambu?.model) {
            BambuModel.A1_MINI -> SlicerTarget.BambuA1Mini
            BambuModel.A1 -> SlicerTarget.BambuA1
            BambuModel.P1S -> SlicerTarget.BambuP1S
            BambuModel.P1P -> SlicerTarget.BambuP1P
            BambuModel.X1C -> SlicerTarget.BambuX1C
            BambuModel.X1E -> SlicerTarget.BambuX1E
            BambuModel.H2D -> SlicerTarget.BambuH2D
            null -> SlicerTarget.BambuA1Mini
        }
        else -> SlicerTarget.SnapmakerU1
    }
```

- [ ] **Step 4: Run the target-selection test to verify it passes**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.slice.SlicerTargetResolverTest --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/slice/SlicerTarget.kt app/src/main/java/com/u1/slicer/slice/SliceTargetResolver.kt app/src/test/java/com/u1/slicer/slice/SlicerTargetResolverTest.kt
git commit -m "feat: add first-class slice target model"
```

### Task 2: Persist and surface slice target selection

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/SessionState.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsBackup.kt`
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`
- Test: `app/src/test/java/com/u1/slicer/data/SessionStateTest.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt`

- [ ] **Step 1: Write failing persistence and UI structure tests**

```kotlin
@Test
fun `session state round trips sliceTargetId`() {
    val original = SessionState(
        modelName = "cube.3mf",
        rawInputPath = "/tmp/cube.3mf",
        sourceModelPath = null,
        currentModelPath = null,
        multiPlateSourcePath = null,
        selectedPlateId = null,
        modelScale = Triple(1f, 1f, 1f),
        modelRotation = Triple(0f, 0f, 0f),
        copyCount = 1,
        customObjectPositions = null,
        customWipeTowerPos = null,
        additionalFiles = emptyList(),
        sliceJobId = null,
        wasSliceComplete = false,
        savedAtEpochMs = 1L,
        appVersionCode = 1,
        sliceTargetId = "BambuA1Mini",
    )

    val restored = SessionState.fromJson(SessionState.toJson(original))
    assertEquals("BambuA1Mini", restored?.sliceTargetId)
}
```

```kotlin
@Test
fun preview_controls_include_slice_target_picker_and_beta_copy() {
    val src = java.io.File("app/src/main/java/com/u1/slicer/MainActivity.kt").readText()
    assertTrue(src.contains("Slice Target"))
    assertTrue(src.contains("Bambu beta"))
}
```

- [ ] **Step 2: Run the persistence/UI tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.data.SessionStateTest --tests com.u1.slicer.ui.printer.F78ConditionalRenderingTest --no-daemon`
Expected: FAIL because `sliceTargetId` and the new UI strings do not exist yet.

- [ ] **Step 3: Add selected target state to the session model and view model**

```kotlin
// SessionState.kt
data class SessionState(
    // existing fields...
    val gcodeToolSpace: String? = null,
    val sliceTargetId: String? = null,
)
```

```kotlin
// SlicerViewModel.kt
private val _selectedSliceTarget = MutableStateFlow<SlicerTarget?>(null)
val selectedSliceTarget: StateFlow<SlicerTarget?> = _selectedSliceTarget.asStateFlow()

fun selectSliceTarget(target: SlicerTarget) {
    _selectedSliceTarget.value = target
}

private fun effectiveSliceTarget(activePrinter: Printer?): SlicerTarget {
    val chosen = _selectedSliceTarget.value
    return if (chosen != null && chosen.isCompatibleWith(activePrinter?.kind ?: PrinterKind.MOONRAKER, activePrinter?.bambu?.model)) {
        chosen
    } else {
        resolveDefaultSliceTarget(activePrinter)
    }
}
```

```kotlin
// MainActivity.kt
Text("Slice Target")
TargetChipRow(
    selected = selectedTarget,
    options = availableTargets,
    onSelected = viewModel::selectSliceTarget,
)
if (selectedTarget.beta) {
    Text(
        "Bambu beta: Snapmaker release behavior stays on the stable Moonraker path.",
        color = MaterialTheme.colorScheme.tertiary,
    )
}
```

- [ ] **Step 4: Run the persistence/UI tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.data.SessionStateTest --tests com.u1.slicer.ui.printer.F78ConditionalRenderingTest --no-daemon`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/main/java/com/u1/slicer/data/SessionState.kt app/src/main/java/com/u1/slicer/data/SettingsBackup.kt app/src/main/java/com/u1/slicer/MainActivity.kt app/src/test/java/com/u1/slicer/data/SessionStateTest.kt app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt
git commit -m "feat: persist and surface slice target selection"
```

### Task 3: Add capability and beta policy routing

**Files:**
- Create: `app/src/main/java/com/u1/slicer/slice/SliceCapabilities.kt`
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`
- Modify: `app/src/test/java/com/u1/slicer/BambuBetaUiPolicyTest.kt`
- Test: `app/src/test/java/com/u1/slicer/slice/SliceCapabilitiesTest.kt`

- [ ] **Step 1: Write the failing capability tests**

```kotlin
package com.u1.slicer.slice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SliceCapabilitiesTest {

    @Test
    fun `snapmaker target keeps stable feature set`() {
        val caps = capabilityProfileFor(SlicerTarget.SnapmakerU1)
        assertTrue(caps.supportsUpload)
        assertTrue(caps.supportsStart)
        assertFalse(caps.beta)
    }

    @Test
    fun `a1 mini target is beta and limited to proven feature set`() {
        val caps = capabilityProfileFor(SlicerTarget.BambuA1Mini)
        assertTrue(caps.supportsUpload)
        assertTrue(caps.supportsStart)
        assertTrue(caps.beta)
        assertFalse(caps.supportsColorMix)
    }
}
```

- [ ] **Step 2: Run the capability tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.slice.SliceCapabilitiesTest --no-daemon`
Expected: FAIL with unresolved references for `capabilityProfileFor` or `supportsColorMix`.

- [ ] **Step 3: Implement the capability profile**

```kotlin
package com.u1.slicer.slice

data class SliceCapabilityProfile(
    val beta: Boolean,
    val supportsUpload: Boolean,
    val supportsStart: Boolean,
    val supportsAmsMapping: Boolean,
    val supportsImportedProcessProfiles: Boolean,
    val supportsColorMix: Boolean,
    val supportsTopSurfaceMixModes: Boolean,
)

fun capabilityProfileFor(target: SlicerTarget): SliceCapabilityProfile =
    when (target) {
        SlicerTarget.SnapmakerU1 -> SliceCapabilityProfile(
            beta = false,
            supportsUpload = true,
            supportsStart = true,
            supportsAmsMapping = false,
            supportsImportedProcessProfiles = true,
            supportsColorMix = true,
            supportsTopSurfaceMixModes = true,
        )
        else -> SliceCapabilityProfile(
            beta = true,
            supportsUpload = true,
            supportsStart = true,
            supportsAmsMapping = true,
            supportsImportedProcessProfiles = false,
            supportsColorMix = false,
            supportsTopSurfaceMixModes = false,
        )
    }
```

- [ ] **Step 4: Wire the beta copy and unsupported-feature guards**

```kotlin
val sliceCapabilities = capabilityProfileFor(selectedTarget)

if (sliceCapabilities.beta) {
    Text("Bambu beta")
    Text("Only the A1 Mini full slice, upload, and start path is currently validated.")
}

if (!sliceCapabilities.supportsColorMix) {
    Text("ColorMix remains disabled for this target for now.")
}
```

- [ ] **Step 5: Run the capability and beta-policy tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.slice.SliceCapabilitiesTest --tests com.u1.slicer.BambuBetaUiPolicyTest --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/slice/SliceCapabilities.kt app/src/main/java/com/u1/slicer/MainActivity.kt app/src/test/java/com/u1/slicer/slice/SliceCapabilitiesTest.kt app/src/test/java/com/u1/slicer/BambuBetaUiPolicyTest.kt
git commit -m "feat: add slice capability and beta policy model"
```

### Task 4: Resolve Bambu-targeted slice config for A1 Mini

**Files:**
- Create: `app/src/main/java/com/u1/slicer/bambu/BambuMachineProfile.kt`
- Create: `app/src/main/java/com/u1/slicer/bambu/BambuTargetedConfigResolver.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/BambuTargetedConfigResolverTest.kt`
- Test: `app/src/test/java/com/u1/slicer/SliceArtifactRoutingTest.kt`

- [ ] **Step 1: Write the failing A1 Mini config-resolution tests**

```kotlin
package com.u1.slicer.bambu

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BambuTargetedConfigResolverTest {

    @Test
    fun `a1 mini target swaps in a1 mini machine envelope`() {
        val base = SliceConfig()

        val resolved = resolveTargetedSliceConfig(
            target = SlicerTarget.BambuA1Mini,
            base = base,
            extruderCount = 1,
            filamentTypes = listOf("PLA"),
        )

        assertEquals(180f, resolved.bedSizeX)
        assertEquals(180f, resolved.bedSizeY)
        assertEquals(180f, resolved.maxPrintHeight)
        assertTrue(resolved.machineStartGcode.isBlank())
    }

    @Test
    fun `snapmaker target remains unchanged`() {
        val base = SliceConfig()
        val resolved = resolveTargetedSliceConfig(
            target = SlicerTarget.SnapmakerU1,
            base = base,
            extruderCount = 1,
            filamentTypes = listOf("PLA"),
        )

        assertEquals(base.bedSizeX, resolved.bedSizeX)
        assertEquals(base.machineStartGcode, resolved.machineStartGcode)
    }
}
```

- [ ] **Step 2: Run the A1 Mini config-resolution test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.bambu.BambuTargetedConfigResolverTest --no-daemon`
Expected: FAIL with unresolved references for `resolveTargetedSliceConfig`.

- [ ] **Step 3: Implement the machine profile and config resolver**

```kotlin
package com.u1.slicer.bambu

import com.u1.slicer.data.SliceConfig
import com.u1.slicer.slice.SlicerTarget

data class BambuMachineProfile(
    val bedSizeX: Float,
    val bedSizeY: Float,
    val maxPrintHeight: Float,
    val nozzleDiameter: Float,
)

private val a1MiniProfile = BambuMachineProfile(
    bedSizeX = 180f,
    bedSizeY = 180f,
    maxPrintHeight = 180f,
    nozzleDiameter = 0.4f,
)

fun resolveTargetedSliceConfig(
    target: SlicerTarget,
    base: SliceConfig,
    extruderCount: Int,
    filamentTypes: List<String>,
): SliceConfig {
    return when (target) {
        SlicerTarget.SnapmakerU1 -> base
        SlicerTarget.BambuA1Mini -> base.copy(
            bedSizeX = a1MiniProfile.bedSizeX,
            bedSizeY = a1MiniProfile.bedSizeY,
            maxPrintHeight = a1MiniProfile.maxPrintHeight,
            nozzleDiameter = a1MiniProfile.nozzleDiameter,
            extruderCount = extruderCount,
            filamentTypes = filamentTypes.toTypedArray(),
            machineStartGcode = "",
            machineEndGcode = "",
        )
        else -> error("Bambu target $target is not implemented in the first A1 Mini wave")
    }
}
```

- [ ] **Step 4: Route `SlicerViewModel.startSlicing()` through the resolver**

```kotlin
val target = effectiveSliceTarget(printersRepo.activePrinter.first())
val resolvedTargetConfig = resolveTargetedSliceConfig(
    target = target,
    base = sliceConfig,
    extruderCount = sliceConfig.extruderCount,
    filamentTypes = sliceConfig.filamentTypes.toList(),
)
val result = native.slice(resolvedTargetConfig)
```

- [ ] **Step 5: Run the A1 Mini and routing tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.bambu.BambuTargetedConfigResolverTest --tests com.u1.slicer.SliceArtifactRoutingTest --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/bambu/BambuMachineProfile.kt app/src/main/java/com/u1/slicer/bambu/BambuTargetedConfigResolver.kt app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/test/java/com/u1/slicer/bambu/BambuTargetedConfigResolverTest.kt app/src/test/java/com/u1/slicer/SliceArtifactRoutingTest.kt
git commit -m "feat: resolve a1 mini targeted slice config"
```

### Task 5: Create typed slice artifacts and write Bambu projects

**Files:**
- Create: `app/src/main/java/com/u1/slicer/slice/SliceArtifact.kt`
- Create: `app/src/main/java/com/u1/slicer/bambu/BambuProjectWriter.kt`
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/BambuProjectFileInspector.kt`
- Test: `app/src/test/java/com/u1/slicer/bambu/BambuProjectWriterTest.kt`
- Test: `app/src/test/java/com/u1/slicer/SliceArtifactRoutingTest.kt`

- [ ] **Step 1: Write the failing Bambu artifact writer test**

```kotlin
package com.u1.slicer.bambu

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class BambuProjectWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `writer creates sendable project with expected entries`() {
        val gcode = tmp.newFile("plate_1.gcode").apply {
            writeText("; generated by test\nG28\n")
        }
        val out = File(tmp.root, "a1-mini-project.3mf")

        BambuProjectWriter.writeSinglePlateProject(
            outputFile = out,
            gcodeFile = gcode,
            modelName = "cube",
            plateId = 1,
            filamentColours = listOf("#FFFFFF"),
            filamentTypes = listOf("PLA"),
        )

        ZipFile(out).use { zip ->
            assertNotNull(zip.getEntry("3D/3dmodel.model"))
            assertNotNull(zip.getEntry("Metadata/project_settings.config"))
            assertNotNull(zip.getEntry("Metadata/plate_1.gcode"))
            assertTrue(zip.getInputStream(zip.getEntry("Metadata/plate_1.gcode")).reader().readText().contains("G28"))
        }
    }
}
```

- [ ] **Step 2: Run the Bambu artifact writer test to verify it fails**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.bambu.BambuProjectWriterTest --no-daemon`
Expected: FAIL with unresolved references for `BambuProjectWriter`.

- [ ] **Step 3: Add the typed artifact model**

```kotlin
package com.u1.slicer.slice

import java.io.File

sealed class SliceArtifact {
    abstract val sourceModelName: String

    data class MoonrakerGcodeArtifact(
        override val sourceModelName: String,
        val gcodeFile: File,
    ) : SliceArtifact()

    data class BambuProjectArtifact(
        override val sourceModelName: String,
        val projectFile: File,
        val plateId: Int,
    ) : SliceArtifact()
}
```

- [ ] **Step 4: Implement the minimal single-plate Bambu project writer**

```kotlin
package com.u1.slicer.bambu

import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object BambuProjectWriter {

    fun writeSinglePlateProject(
        outputFile: File,
        gcodeFile: File,
        modelName: String,
        plateId: Int,
        filamentColours: List<String>,
        filamentTypes: List<String>,
    ) {
        ZipOutputStream(outputFile.outputStream().buffered()).use { zip ->
            write(zip, "_rels/.rels", RELS_XML)
            write(zip, "3D/3dmodel.model", MODEL_XML)
            write(zip, "Metadata/model_settings.config", MODEL_SETTINGS_XML)
            write(zip, "Metadata/project_settings.config", JSONObject().apply {
                put("model_name", modelName)
                put("filament_colour", filamentColours)
                put("filament_type", filamentTypes)
                put("filament_count", filamentColours.size.toString())
            }.toString())
            write(zip, "Metadata/plate_${plateId}.gcode", gcodeFile.readText())
            write(zip, "[Content_Types].xml", CONTENT_TYPES_XML)
        }
    }

    private fun write(zip: ZipOutputStream, path: String, body: String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body.toByteArray())
        zip.closeEntry()
    }

    private const val RELS_XML = """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Target="/3D/3dmodel.model" Id="rel0" Type="http://schemas.microsoft.com/3dmanufacturing/2013/01/3dmodel"/></Relationships>"""
    private const val MODEL_XML = """<?xml version="1.0" encoding="UTF-8"?><model unit="millimeter" xmlns="http://schemas.microsoft.com/3dmanufacturing/core/2015/02"><resources><object id="1" type="model"><mesh><vertices/><triangles/></mesh></object></resources><build><item objectid="1"/></build></model>"""
    private const val MODEL_SETTINGS_XML = """<?xml version="1.0" encoding="UTF-8"?><config><object id="1"><metadata type="object" key="extruder" value="1"/></object></config>"""
    private const val CONTENT_TYPES_XML = """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="model" ContentType="application/vnd.ms-package.3dmanufacturing-3dmodel+xml"/></Types>"""
}
```

- [ ] **Step 5: Build the typed artifact in `SlicerViewModel` after a successful slice**

```kotlin
val artifact = when (target) {
    SlicerTarget.SnapmakerU1 -> SliceArtifact.MoonrakerGcodeArtifact(
        sourceModelName = currentModelName,
        gcodeFile = File(result.gcodePath),
    )
    SlicerTarget.BambuA1Mini -> {
        val out = File(context.cacheDir, "${currentModelName.removeSuffix(".3mf")}.bambu.3mf")
        BambuProjectWriter.writeSinglePlateProject(
            outputFile = out,
            gcodeFile = File(result.gcodePath),
            modelName = currentModelName,
            plateId = 1,
            filamentColours = resolveArtifactColours(),
            filamentTypes = resolveArtifactTypes(),
        )
        SliceArtifact.BambuProjectArtifact(
            sourceModelName = currentModelName,
            projectFile = out,
            plateId = 1,
        )
    }
    else -> error("Unsupported true Bambu target in first milestone")
}
_latestSliceArtifact.value = artifact
```

- [ ] **Step 6: Run the artifact tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.bambu.BambuProjectWriterTest --tests com.u1.slicer.SliceArtifactRoutingTest --no-daemon`
Expected: PASS

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/slice/SliceArtifact.kt app/src/main/java/com/u1/slicer/bambu/BambuProjectWriter.kt app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/main/java/com/u1/slicer/printer/BambuProjectFileInspector.kt app/src/test/java/com/u1/slicer/bambu/BambuProjectWriterTest.kt app/src/test/java/com/u1/slicer/SliceArtifactRoutingTest.kt
git commit -m "feat: generate typed slice artifacts including bambu projects"
```

### Task 6: Route preview send/start through typed artifacts

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`
- Modify: `app/src/test/java/com/u1/slicer/BambuBetaUiPolicyTest.kt`
- Modify: `app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt`
- Test: `app/src/test/java/com/u1/slicer/printer/PrinterViewModelTest.kt`

- [ ] **Step 1: Write the failing send-routing tests**

```kotlin
@Test
fun `bambu target routes send through generated project artifact`() {
    val src = java.io.File("app/src/main/java/com/u1/slicer/MainActivity.kt").readText()
    assertTrue(src.contains("latestSliceArtifact"))
    assertTrue(src.contains("BambuProjectArtifact"))
}
```

```kotlin
@Test
fun `printer view model preserves stable gcode send path`() {
    assertEquals(
        "model.gcode",
        PrinterRepository.resolveUploadBaseName("model.gcode", "fallback.gcode"),
    )
}
```

- [ ] **Step 2: Run the send-routing tests to verify they fail**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.BambuBetaUiPolicyTest --tests com.u1.slicer.ui.printer.F78ConditionalRenderingTest --tests com.u1.slicer.printer.PrinterViewModelTest --no-daemon`
Expected: FAIL because `MainActivity` still routes Bambu through passthrough inspection.

- [ ] **Step 3: Replace passthrough-first send gating with typed artifact routing**

```kotlin
val latestArtifact = viewModel.latestSliceArtifact.collectAsState().value
when (latestArtifact) {
    is SliceArtifact.MoonrakerGcodeArtifact -> {
        val physical = PhysicalGcodePath.of(latestArtifact.gcodeFile)
        printerViewModel.sendAndPrint(physical, latestArtifact.sourceModelName)
    }
    is SliceArtifact.BambuProjectArtifact -> {
        printerViewModel.sendBambuProjectAndPrint(
            projectFile = latestArtifact.projectFile,
            modelName = latestArtifact.sourceModelName,
            plateId = latestArtifact.plateId,
            amsMapping = resolvedAmsMapping,
            useAms = true,
        )
    }
    null -> printerViewModel.reportSendError("Nothing has been sliced yet")
}
```

- [ ] **Step 4: Keep compatibility checks strict before send**

```kotlin
val active = activePrinter
val selectedTarget = viewModel.selectedSliceTarget.value ?: resolveDefaultSliceTarget(active)
val targetCompatible = selectedTarget.isCompatibleWith(active?.kind ?: PrinterKind.MOONRAKER, active?.bambu?.model)
if (!targetCompatible) {
    printerViewModel.reportSendError("The selected slice target does not match the connected printer.")
    return@Button
}
```

- [ ] **Step 5: Run the send-routing tests to verify they pass**

Run: `.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.BambuBetaUiPolicyTest --tests com.u1.slicer.ui.printer.F78ConditionalRenderingTest --tests com.u1.slicer.printer.PrinterViewModelTest --no-daemon`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt app/src/test/java/com/u1/slicer/BambuBetaUiPolicyTest.kt app/src/test/java/com/u1/slicer/ui/printer/F78ConditionalRenderingTest.kt app/src/test/java/com/u1/slicer/printer/PrinterViewModelTest.kt
git commit -m "feat: route send flow through typed slice artifacts"
```

### Task 7: Verify Snapmaker regression safety and A1 Mini proving flow

**Files:**
- Modify: `docs/superpowers/specs/2026-07-04-true-bambu-slicing-design.md` only if implementation evidence changes validation wording
- Test: existing focused regression suites

- [ ] **Step 1: Run the focused Bambu + Snapmaker JVM sweep**

Run:

```bash
.\gradlew.bat testDebugUnitTest --tests com.u1.slicer.slice.SlicerTargetResolverTest --tests com.u1.slicer.slice.SliceCapabilitiesTest --tests com.u1.slicer.bambu.BambuTargetedConfigResolverTest --tests com.u1.slicer.bambu.BambuProjectWriterTest --tests com.u1.slicer.SliceArtifactRoutingTest --tests com.u1.slicer.BambuBetaUiPolicyTest --tests com.u1.slicer.printer.DefaultBambuLanClientTest --tests com.u1.slicer.printer.BambuLanTransportTest --tests com.u1.slicer.printer.PrinterRepositoryTest --tests com.u1.slicer.printer.PrinterViewModelTest --tests com.u1.slicer.ui.printer.F78ConditionalRenderingTest --no-daemon
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 2: Run the full JVM regression suite**

Run: `.\gradlew.bat testDebugUnitTest --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Build a debug APK for device validation**

Run: `.\gradlew.bat assembleDebug --no-daemon`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Install to the Pixel 8a**

Run: `adb -s 43211JEKB16931 install -r D:\projects\u1-slicer-for-android\.worktrees\bambu-support\app\build\outputs\apk\debug\app-debug.apk`
Expected: `Success`

- [ ] **Step 5: Validate the A1 Mini proving flow without violating printer-safety rules**

Run:

```bash
adb -s 43211JEKB16931 shell am broadcast -a com.u1.slicer.orca.DUMP_STATE
```

Expected: state dump confirms:
- selected slice target is `BambuA1Mini`
- latest artifact is a generated `.3mf`
- send UI exposes Bambu beta wording

Manual device checklist:
- Slice a simple STL while `Bambu A1 Mini` is selected
- Confirm Preview send buttons talk about a generated Bambu project, not original passthrough-only upload
- Use `Upload Only` first
- Do **not** trigger `Map & Print` / `Send & Print` on the physical printer without explicit user approval

- [ ] **Step 6: Commit**

```bash
git add docs/superpowers/specs/2026-07-04-true-bambu-slicing-design.md
git commit -m "test: verify true bambu slicing against snapmaker regressions"
```

## Self-Review

### Spec coverage

- Goal and product decision: covered by Tasks 1, 4, 5, and 6.
- Target and printer relationship: covered by Tasks 1 and 6.
- Capability and parity model: covered by Task 3.
- Bambu config resolution: covered by Task 4.
- Typed artifact model: covered by Task 5.
- Full-loop execution on A1 Mini: covered by Tasks 6 and 7.
- Snapmaker regression protection: covered by Task 7.

### Placeholder scan

- No `TODO`
- No `TBD`
- No "write tests for the above"
- No "similar to Task N"

### Type consistency

- `SlicerTarget` is defined in Task 1 and reused consistently.
- `SliceCapabilityProfile` and `capabilityProfileFor(...)` are defined in Task 3 and reused consistently.
- `resolveTargetedSliceConfig(...)` is defined in Task 4 and reused consistently.
- `SliceArtifact.BambuProjectArtifact` is defined in Task 5 and reused consistently.

Plan complete and saved to `docs/superpowers/plans/2026-07-04-true-bambu-slicing-implementation.md`. The user already asked me to continue autonomously, so the next step is inline execution of Task 1 in this session.
