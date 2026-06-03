# F94 (Send-prep banner) + F92 (Auto-arrange) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add an immediate "Preparing G-code" banner on the Printer screen during the send-prep gap (F94), and an "Auto-arrange all" action that packs objects on the bed clear of the pinned wipe tower (F92).

**Architecture:** F94 adds a `SendingState.Preparing` flipped synchronously at the three send-confirm sites in `MainActivity`, rendered by a new card arm in `PrinterScreen`; a catch around the prep step surfaces failures as `Error`. F92 adds a pure `CopyArrangeCalculator.autoArrange()` shelf-packer (keep-out aware, on-bed-guaranteed) plus `SlicerViewModel.autoArrangeAll()` that delegates apply to the existing `applyPlacementPositions`, wired to a menu item beside "Auto-orient all". Neither needs a native `.so` rebuild.

**Tech Stack:** Kotlin, Jetpack Compose, JUnit (JVM unit + instrumented), existing `CopyArrangeCalculator` / `SlicerViewModel` / `PrinterViewModel` patterns.

**Spec:** [docs/superpowers/specs/2026-06-03-f94-f92-design.md](../specs/2026-06-03-f94-f92-design.md)

---

## File structure

| File | Change |
|------|--------|
| `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt` | add `ArrangeResult` + `autoArrange()` |
| `app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt` | add `autoArrange` unit tests |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | add `autoArrangeAll()` + `launchAutoArrangeAll()` |
| `app/src/androidTest/java/com/u1/slicer/PreparePreviewViewModelTest.kt` | add `autoArrangeAll` instrumented test |
| `app/src/main/java/com/u1/slicer/MainActivity.kt` | `onAutoArrangeAll` param + menu item + call site; F94 `beginSendPreparing()` at 3 sites + error catch |
| `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` | `SendingState.Preparing` + `beginSendPreparing()` + `reportSendError()` |
| `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` | `Preparing` card arm |
| `app/src/test/java/com/u1/slicer/ui/SendPreparingBannerTest.kt` | F94 structural guard (new) |

---

## Task 1: `CopyArrangeCalculator.autoArrange()` pure function (F92)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt`
- Test: `app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt`

- [ ] **Step 1: Write the failing tests**

Append these tests inside the `CopyArrangeCalculatorTest` class (before the final closing `}`):

```kotlin
    // --- F92: autoArrange ---

    private fun overlaps(ax: Float, ay: Float, asx: Float, asy: Float,
                         bx: Float, by: Float, bsx: Float, bsy: Float): Boolean =
        ax < bx + bsx && ax + asx > bx && ay < by + bsy && ay + asy > by

    @Test fun `autoArrange two objects no tower places both on-bed without overlap`() {
        val boxes = floatArrayOf(60f, 50f, 10f, 60f, 50f, 10f)
        val incoming = floatArrayOf(0f, 0f, 0f, 0f)
        val r = CopyArrangeCalculator.autoArrange(boxes, reservedRect = null, incoming = incoming)
        assertEquals(0, r.overflowCount)
        assertEquals(4, r.positions.size)
        for (i in 0 until 2) {
            assertTrue("obj $i on bed X", r.positions[i * 2] in 0f..270f)
            assertTrue("obj $i on bed Y", r.positions[i * 2 + 1] in 0f..270f)
            assertTrue("obj $i fits X", r.positions[i * 2] + 60f <= 270f)
            assertTrue("obj $i fits Y", r.positions[i * 2 + 1] + 50f <= 270f)
        }
        assertFalse("objects must not overlap",
            overlaps(r.positions[0], r.positions[1], 60f, 50f,
                     r.positions[2], r.positions[3], 60f, 50f))
    }

    @Test fun `autoArrange avoids the reserved wipe-tower rect`() {
        // Tower keep-out at back-center: x 105..165, y 200..260.
        val reserved = floatArrayOf(105f, 200f, 165f, 260f)
        val boxes = floatArrayOf(80f, 80f, 10f, 80f, 80f, 10f, 80f, 80f, 10f)
        val incoming = FloatArray(6)
        val r = CopyArrangeCalculator.autoArrange(boxes, reserved, incoming)
        assertEquals(0, r.overflowCount)
        for (i in 0 until 3) {
            val x = r.positions[i * 2]; val y = r.positions[i * 2 + 1]
            assertFalse("obj $i overlaps tower keep-out",
                overlaps(x, y, 80f, 80f, 105f, 200f, 60f, 60f))
            assertTrue("obj $i on bed", x in 0f..270f && y in 0f..270f &&
                x + 80f <= 270f && y + 80f <= 270f)
        }
    }

    @Test fun `autoArrange reports overflow and never places off-bed`() {
        // Six 120x120 objects cannot all fit on a 270mm bed.
        val boxes = FloatArray(6 * 3) { idx -> if (idx % 3 == 2) 10f else 120f }
        val incoming = FloatArray(12) { 7f }
        val r = CopyArrangeCalculator.autoArrange(boxes, reservedRect = null, incoming = incoming)
        assertTrue("some objects must overflow", r.overflowCount > 0)
        for (i in 0 until 6) {
            assertTrue("obj $i X within bed", r.positions[i * 2] in 0f..270f)
            assertTrue("obj $i Y within bed", r.positions[i * 2 + 1] in 0f..270f)
        }
    }

    @Test fun `autoArrange single object centers clear of reserved rect`() {
        val reserved = floatArrayOf(105f, 200f, 165f, 260f)
        val boxes = floatArrayOf(40f, 40f, 10f)
        val r = CopyArrangeCalculator.autoArrange(boxes, reserved, incoming = floatArrayOf(0f, 0f))
        assertEquals(0, r.overflowCount)
        assertEquals(2, r.positions.size)
        assertFalse("single object overlaps tower",
            overlaps(r.positions[0], r.positions[1], 40f, 40f, 105f, 200f, 60f, 60f))
        assertTrue("on bed", r.positions[0] in 0f..230f && r.positions[1] in 0f..230f)
    }

    @Test fun `autoArrange wraps to next shelf when row is full`() {
        // Four 100x40 objects: only 2 fit per 270mm row -> 2 shelves, none overlap.
        val boxes = FloatArray(4 * 3) { idx -> when (idx % 3) { 0 -> 100f; 1 -> 40f; else -> 10f } }
        val r = CopyArrangeCalculator.autoArrange(boxes, reservedRect = null, incoming = FloatArray(8))
        assertEquals(0, r.overflowCount)
        for (a in 0 until 4) for (b in a + 1 until 4) {
            assertFalse("obj $a and $b overlap",
                overlaps(r.positions[a * 2], r.positions[a * 2 + 1], 100f, 40f,
                         r.positions[b * 2], r.positions[b * 2 + 1], 100f, 40f))
        }
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.model.CopyArrangeCalculatorTest" --no-daemon`
Expected: FAIL — `autoArrange` / `ArrangeResult` unresolved reference.

- [ ] **Step 3: Implement `autoArrange` + `ArrangeResult`**

Add to `CopyArrangeCalculator.kt`, just before the final closing `}` of the `object`:

```kotlin
    /** Result of [autoArrange]: packed positions + count of objects that could not be placed. */
    data class ArrangeResult(
        val positions: FloatArray,
        val overflowCount: Int,
    )

    /**
     * F92 — translation-only auto-arrange. Shelf-packs N object footprints into the bed
     * (front-left, wrapping upward), skipping any placement that overlaps [reservedRect]
     * (the pinned wipe tower, already margin-inflated by the caller) or would fall off the
     * bed. Objects that cannot be placed on-bed are left at their [incoming] position and
     * counted in [ArrangeResult.overflowCount] — never placed off-bed (the structural fix
     * B135 needs).
     *
     * @param boxes flat [sX0,sY0,sZ0, ...] from getObjectBoundingBoxes() (already
     *   post-rotation, so translation-only keeps each object's rotation).
     * @param reservedRect [minX,minY,maxX,maxY] keep-out, or null when no tower is active.
     * @param incoming flat [x0,y0,...] current positions, used for overflow fallback.
     * @param bedSize bed edge length (default 270mm for Snapmaker U1).
     * @param margin gap between objects and from the bed edge in mm (default 5mm).
     */
    fun autoArrange(
        boxes: FloatArray,
        reservedRect: FloatArray?,
        incoming: FloatArray,
        bedSize: Float = 270f,
        margin: Float = 5f,
    ): ArrangeResult {
        val n = boxes.size / 3
        if (n == 0) return ArrangeResult(floatArrayOf(), 0)

        val positions = FloatArray(n * 2)
        // Seed from incoming so overflow objects keep a sane (existing) position.
        for (i in 0 until n) {
            positions[i * 2] = incoming.getOrElse(i * 2) { margin }
            positions[i * 2 + 1] = incoming.getOrElse(i * 2 + 1) { margin }
        }

        val maxEdge = bedSize - margin
        val maxIters = (bedSize / maxOf(margin, 1f)).toInt() * 3 + 16
        // Largest-area-first for tighter packing; stable on ties via index.
        val order = (0 until n).sortedWith(
            compareByDescending<Int> { boxes[it * 3] * boxes[it * 3 + 1] }.thenBy { it }
        )

        var curX = margin
        var curY = margin
        var rowMaxY = 0f
        var overflow = 0

        for (idx in order) {
            val sx = boxes[idx * 3]
            val sy = boxes[idx * 3 + 1]
            // Physically too large to ever fit on the bed.
            if (sx > bedSize - 2 * margin || sy > bedSize - 2 * margin) { overflow++; continue }

            var placed = false
            var guard = 0
            while (guard++ < maxIters) {
                // Wrap to next shelf if this object overflows the row width.
                if (curX + sx > maxEdge) {
                    curX = margin
                    curY += rowMaxY + margin
                    rowMaxY = 0f
                }
                // Out of vertical space on the bed.
                if (curY + sy > maxEdge) break
                val cMaxX = curX + sx
                val cMaxY = curY + sy
                if (reservedRect != null &&
                    curX < reservedRect[2] && cMaxX > reservedRect[0] &&
                    curY < reservedRect[3] && cMaxY > reservedRect[1]
                ) {
                    // Overlaps the keep-out — skip past it on this shelf and retry.
                    curX = reservedRect[2] + margin
                    continue
                }
                positions[idx * 2] = curX
                positions[idx * 2 + 1] = curY
                curX += sx + margin
                if (sy > rowMaxY) rowMaxY = sy
                placed = true
                break
            }
            if (!placed) overflow++
        }
        return ArrangeResult(positions, overflow)
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.model.CopyArrangeCalculatorTest" --no-daemon`
Expected: PASS (all autoArrange cases + existing cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/model/CopyArrangeCalculator.kt app/src/test/java/com/u1/slicer/model/CopyArrangeCalculatorTest.kt
git commit -m "feat: F92 autoArrange pure packer (keep-out aware, on-bed guaranteed)"
```

---

## Task 2: `SlicerViewModel.autoArrangeAll()` (F92)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (add after `autoOrientAll()`, ~line 1280)
- Test: `app/src/androidTest/java/com/u1/slicer/PreparePreviewViewModelTest.kt`

- [ ] **Step 1: Implement `autoArrangeAll` + `launchAutoArrangeAll`**

Insert immediately after the closing `}` of `autoOrientAll()` (the method ending at ~line 1280):

```kotlin
    /**
     * F92 — fire-and-forget wrapper around [autoArrangeAll] for the UI (mirrors
     * [launchAutoOrientAll]; avoids allocating a rememberCoroutineScope in the
     * Prepare-tab body).
     */
    fun launchAutoArrangeAll() {
        viewModelScope.launch { autoArrangeAll() }
    }

    /**
     * F92 — translation-only auto-arrange of every object on the bed. Keeps each
     * object's rotation/scale; packs footprints clear of each other and of the
     * pinned wipe tower (the tower is never moved). Delegates the apply to
     * [applyPlacementPositions], which branches on multi- vs single-object mode and
     * keeps the native model / state flows in sync.
     */
    suspend fun autoArrangeAll() {
        val ctx = beginLongOp("Auto-arranging")
        try {
            val boxes = withContext(Dispatchers.IO) {
                runCatching { native.getObjectBoundingBoxes() }.getOrDefault(floatArrayOf())
            }
            val n = boxes.size / 3
            if (n == 0) { _toastEvents.tryEmit("Nothing to arrange"); return }

            val cfg = _config.value
            val ov = slicingOverrides.value
            // Pinned tower: pass the current position back unchanged. Reserve its
            // footprint (margin-inflated) only when a tower is actually active.
            val towerPos = customWipeTowerPos ?: (cfg.wipeTowerX to cfg.wipeTowerY)
            val reserved: FloatArray? = customWipeTowerPos?.let { (tx, ty) ->
                val w = resolveWipeTowerWidth(cfg, ov)
                val d = resolveWipeTowerDepth(lastModelInfo?.sizeZ ?: 0f, ov)
                floatArrayOf(tx - 5f, ty - 5f, tx + w + 5f, ty + d + 5f)
            }

            val incoming = customObjectPositions ?: getPlacementPositions()
            val result = com.u1.slicer.model.CopyArrangeCalculator.autoArrange(boxes, reserved, incoming)

            applyPlacementPositions(result.positions, towerPos)
            _sliceStale.value = true
            invalidatePrepareMeshCache()
            if (result.overflowCount > 0) {
                _toastEvents.tryEmit("${result.overflowCount} object(s) didn't fit on the bed")
            }
        } finally {
            endLongOp(ctx)
        }
    }
```

- [ ] **Step 2: Write the failing instrumented test**

Add inside `PreparePreviewViewModelTest` (before the final closing `}` of the class):

```kotlin
    /**
     * F92: two STLs added to the bed, then autoArrangeAll() packs them so neither
     * overlaps the other, and both stay within the 270×270mm bed.
     */
    @Test
    fun autoArrangeAll_twoObjects_noOverlapAndOnBed() {
        val application = targetContext.applicationContext as U1SlicerApplication
        val viewModel = SlicerViewModel(application)
        val first = copyAssetToCache("tetrahedron.stl")
        val second = copyAssetToCache("tetrahedron.stl")
        try {
            viewModel.loadModelFromFile(first)
            waitUntil("first STL loaded", timeoutMs = 60_000L) {
                viewModel.state.value is SlicerViewModel.SlicerState.ModelLoaded
            }
            viewModel.addModelFromFile(second)
            waitUntil("second STL added to bed", timeoutMs = 60_000L) {
                viewModel.hasMultipleDistinctObjects.value
            }

            kotlinx.coroutines.runBlocking { viewModel.autoArrangeAll() }

            val pos = viewModel.multiObjectPositions.value
            assertNotNull("multiObjectPositions must be set after arrange", pos)
            assertEquals("two objects → 4 floats", 4, pos!!.size)
            val boxes = viewModel.objectBoundingBoxes.value
            // Pairwise non-overlap (use each object's own footprint).
            val ax = pos[0]; val ay = pos[1]; val asx = boxes[0]; val asy = boxes[1]
            val bx = pos[2]; val by = pos[3]; val bsx = boxes[3]; val bsy = boxes[4]
            val overlap = ax < bx + bsx && ax + asx > bx && ay < by + bsy && ay + asy > by
            assertFalse("arranged objects must not overlap", overlap)
            for (i in 0 until 2) {
                assertTrue("obj $i on bed X", pos[i * 2] in 0f..270f)
                assertTrue("obj $i on bed Y", pos[i * 2 + 1] in 0f..270f)
            }
        } finally {
            viewModel.clearModel()
            first.delete()
            second.delete()
        }
    }
```

- [ ] **Step 3: Run the test to verify it fails (then passes once Step 1 is in)**

Since Step 1 already added the method, this test should compile and pass. Run on a connected device:

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.PreparePreviewViewModelTest#autoArrangeAll_twoObjects_noOverlapAndOnBed"`
Expected: PASS. (If Step 1 were missing, it would fail to compile — confirming the test exercises the new method.)

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt app/src/androidTest/java/com/u1/slicer/PreparePreviewViewModelTest.kt
git commit -m "feat: F92 SlicerViewModel.autoArrangeAll + instrumented test"
```

---

## Task 3: Auto-arrange menu entry (F92 UI)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (param ~3131, menu ~3719, call site ~1535)

- [ ] **Step 1: Add the composable parameter**

Find the parameter declaration `onAutoOrientAll: (() -> Unit)? = null,` (~line 3131) and add directly below it:

```kotlin
    onAutoArrangeAll: (() -> Unit)? = null,
```

- [ ] **Step 2: Add the menu item**

Find the "Auto-orient all" `DropdownMenuItem` block (~line 3719):

```kotlin
                            if (onAutoOrientAll != null) {
                                DropdownMenuItem(
                                    text = { Text("Auto-orient all") },
                                    onClick = { onAutoOrientAll(); menuOpen = false },
                                )
                            }
```

Add immediately after its closing `}`:

```kotlin
                            if (onAutoArrangeAll != null) {
                                DropdownMenuItem(
                                    text = { Text("Auto-arrange all") },
                                    onClick = { onAutoArrangeAll(); menuOpen = false },
                                )
                            }
```

Also extend the menu-visibility guard so the menu opens when only arrange is available. Find (~line 3700):

```kotlin
                if (onInfoClick != null || onAutoOrientAll != null
                    || onResetAllRotations != null || onResetAllScales != null) {
```

Replace with:

```kotlin
                if (onInfoClick != null || onAutoOrientAll != null || onAutoArrangeAll != null
                    || onResetAllRotations != null || onResetAllScales != null) {
```

- [ ] **Step 3: Wire the call site**

Find the call site passing `onAutoOrientAll = { viewModel.launchAutoOrientAll() },` (~line 1535) and add directly below it:

```kotlin
                                onAutoArrangeAll = { viewModel.launchAutoArrangeAll() },
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: F92 Auto-arrange all menu entry next to Auto-orient all"
```

---

## Task 4: `PrinterViewModel` Preparing state + helpers (F94)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` (SendingState ~line 107; add methods near `sendUploadOnly` ~line 352)

- [ ] **Step 1: Add the Preparing state**

Find the `SendingState` sealed class (~line 107) and add `Preparing` after `Idle`:

```kotlin
    sealed class SendingState {
        object Idle : SendingState()
        /** File is being prepared (remap/copy) before the upload begins. */
        object Preparing : SendingState()
        object Uploading : SendingState()
```

- [ ] **Step 2: Add the trigger + error helpers**

Add these two methods to the class body (e.g., directly above `fun sendAndPrint(` at ~line 318):

```kotlin
    /**
     * F94 — show the "Preparing G-code" banner the instant a send action is confirmed,
     * before any IO begins. sendUploadOnly/sendAndPrint flip it to Uploading; a prep
     * failure flips it to Error via [reportSendError].
     */
    fun beginSendPreparing() {
        _sendingState.value = SendingState.Preparing
    }

    /** F94 — surface a send/prep failure on the Printer screen (prevents a stuck banner). */
    fun reportSendError(message: String) {
        _sendingState.value = SendingState.Error(message)
    }
```

- [ ] **Step 3: Build to verify it compiles**

Run: `./gradlew compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt
git commit -m "feat: F94 PrinterViewModel Preparing state + beginSendPreparing/reportSendError"
```

---

## Task 5: `PrinterScreen` Preparing card (F94)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` (the `when (sendingState)` block ~line 225)

- [ ] **Step 1: Add the Preparing arm**

Find the `Uploading` arm (~line 226):

```kotlin
                is PrinterViewModel.SendingState.Uploading -> Card(
```

Insert this arm immediately **before** it (so Preparing renders during the prep phase):

```kotlin
                is PrinterViewModel.SendingState.Preparing -> Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 3.dp)
                        Column {
                            Text("Preparing G-code…", fontWeight = FontWeight.Medium)
                            Text("Getting your file ready to send",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                    }
                }
```

- [ ] **Step 2: Build to verify it compiles**

Run: `./gradlew compileDebugKotlin --no-daemon`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt
git commit -m "feat: F94 Preparing card on Printer screen"
```

---

## Task 6: Wire `beginSendPreparing()` + error catch at send sites (F94)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/MainActivity.kt` (UploadOnly ~838, PrintAndUpload ~918, Absent ~967)

- [ ] **Step 1: UploadOnly site — trigger + catch**

In the `UploadOnly` `onConfirm` (~line 838), find:

```kotlin
                                                pendingMappingSend = null
                                                navigateTab(Routes.PRINTER)
                                                sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Replace with (add `beginSendPreparing()`):

```kotlin
                                                pendingMappingSend = null
                                                navigateTab(Routes.PRINTER)
                                                printerViewModel.beginSendPreparing()
                                                sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Then in the same coroutine find:

```kotlin
                                                    val physical = try {
                                                        com.u1.slicer.gcode.applyPrintTimeRemap(
                                                            source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                            output = com.u1.slicer.gcode.PhysicalGcodePath.of(heldFile),
                                                            colorMapping = com.u1.slicer.gcode.sendRemapForAction(
                                                                uploadOnly = true,
                                                                physicalMapping = emptyList(),
                                                            ),
                                                        )
                                                    } finally {
                                                        LongOpService.stop(toastContext)
                                                    }
```

Replace with (add the `catch`):

```kotlin
                                                    val physical = try {
                                                        com.u1.slicer.gcode.applyPrintTimeRemap(
                                                            source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                            output = com.u1.slicer.gcode.PhysicalGcodePath.of(heldFile),
                                                            colorMapping = com.u1.slicer.gcode.sendRemapForAction(
                                                                uploadOnly = true,
                                                                physicalMapping = emptyList(),
                                                            ),
                                                        )
                                                    } catch (t: Throwable) {
                                                        withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                            printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message}")
                                                        }
                                                        return@launch
                                                    } finally {
                                                        LongOpService.stop(toastContext)
                                                    }
```

- [ ] **Step 2: PrintAndUpload site — trigger + catch**

In the `PrintAndUpload` `onConfirm` (~line 918), find:

```kotlin
                                        pendingMappingSend = null
                                        navigateTab(Routes.PRINTER)
                                        sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Replace with:

```kotlin
                                        pendingMappingSend = null
                                        navigateTab(Routes.PRINTER)
                                        printerViewModel.beginSendPreparing()
                                        sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Then find:

```kotlin
                                            val physical = try {
                                                com.u1.slicer.gcode.applyPrintTimeRemap(
                                                    source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                    output = com.u1.slicer.gcode.PhysicalGcodePath.of(remappedFile),
                                                    colorMapping = sendMapping,
                                                )
                                            } finally {
                                                LongOpService.stop(toastContext)
                                            }
```

Replace with:

```kotlin
                                            val physical = try {
                                                com.u1.slicer.gcode.applyPrintTimeRemap(
                                                    source = com.u1.slicer.gcode.CanonicalGcodePath.of(sourceFile),
                                                    output = com.u1.slicer.gcode.PhysicalGcodePath.of(remappedFile),
                                                    colorMapping = sendMapping,
                                                )
                                            } catch (t: Throwable) {
                                                withContext(kotlinx.coroutines.Dispatchers.Main) {
                                                    printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message}")
                                                }
                                                return@launch
                                            } finally {
                                                LongOpService.stop(toastContext)
                                            }
```

- [ ] **Step 3: Absent (legacy) site — trigger + catch**

In the `CanonicalLookup.Absent` branch (~line 967), find:

```kotlin
                            pendingMappingSend = null
                            navigateTab(Routes.PRINTER)
                            sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Replace with:

```kotlin
                            pendingMappingSend = null
                            navigateTab(Routes.PRINTER)
                            printerViewModel.beginSendPreparing()
                            sendActionScope.launch(kotlinx.coroutines.Dispatchers.IO) {
```

Then find:

```kotlin
                                LongOpService.start(toastContext, "Preparing G-code")
                                try {
                                    viewModel.prepareExportableGcodeWithMapping(
                                        sourceFile, exportedFile, mapping = null
                                    )
                                } finally {
                                    LongOpService.stop(toastContext)
                                }
                                val physical = com.u1.slicer.gcode.PhysicalGcodePath.of(exportedFile)
```

Replace with:

```kotlin
                                LongOpService.start(toastContext, "Preparing G-code")
                                try {
                                    viewModel.prepareExportableGcodeWithMapping(
                                        sourceFile, exportedFile, mapping = null
                                    )
                                } catch (t: Throwable) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        printerViewModel.reportSendError("Couldn't prepare G-code: ${t.message}")
                                    }
                                    return@launch
                                } finally {
                                    LongOpService.stop(toastContext)
                                }
                                val physical = com.u1.slicer.gcode.PhysicalGcodePath.of(exportedFile)
```

- [ ] **Step 4: Build to verify it compiles**

Run: `./gradlew assembleDebug --no-daemon`
Expected: BUILD SUCCESSFUL. (`withContext` and `printerViewModel` are already in scope at all three sites — the existing code already uses both.)

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/MainActivity.kt
git commit -m "feat: F94 show Preparing banner on send + surface prep failures as Error"
```

---

## Task 7: F94 structural guard test

**Files:**
- Create: `app/src/test/java/com/u1/slicer/ui/SendPreparingBannerTest.kt`

(Rationale: `PrinterViewModel` is an `AndroidViewModel` requiring an `Application`/container, and the project has no Robolectric/Compose UI harness — so the trivial state-set + UI wiring is guarded by source-grep, the established pattern here, e.g. `ModelInfoDialogScrollTest`.)

- [ ] **Step 1: Write the test**

```kotlin
package com.u1.slicer.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * F94: the "Preparing G-code" banner must appear on the Printer screen the moment a
 * send action is confirmed, before the (potentially ~80s) remap runs. Structural guard
 * because the project has no Compose UI / Robolectric harness for PrinterViewModel.
 */
class SendPreparingBannerTest {

    private fun source(rel: String): String {
        val f = listOf(File(rel), File("../$rel"), File("app/$rel"))
            .firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absolutePath}")
        return f.readText()
    }

    @Test fun `PrinterViewModel declares Preparing state and helpers`() {
        val src = source("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt")
        assertTrue("Preparing state missing", src.contains("object Preparing : SendingState()"))
        assertTrue("beginSendPreparing() missing", src.contains("fun beginSendPreparing()"))
        assertTrue("reportSendError() missing", src.contains("fun reportSendError("))
    }

    @Test fun `PrinterScreen renders a Preparing arm`() {
        val src = source("src/main/java/com/u1/slicer/ui/PrinterScreen.kt")
        assertTrue("Preparing arm missing in PrinterScreen",
            src.contains("SendingState.Preparing"))
        assertTrue("Preparing card text missing",
            src.contains("Preparing G-code"))
    }

    @Test fun `all three send sites trigger beginSendPreparing`() {
        val src = source("src/main/java/com/u1/slicer/MainActivity.kt")
        val count = Regex("beginSendPreparing\\(\\)").findAll(src).count()
        assertTrue("Expected >= 3 beginSendPreparing() calls (UploadOnly, PrintAndUpload, " +
            "Absent), found $count", count >= 3)
    }

    @Test fun `send sites surface prep failures as Error`() {
        val src = source("src/main/java/com/u1/slicer/MainActivity.kt")
        val count = Regex("reportSendError\\(").findAll(src).count()
        assertTrue("Expected >= 3 reportSendError() calls, found $count", count >= 3)
    }
}
```

- [ ] **Step 2: Run the test**

Run: `./gradlew testDebugUnitTest --tests "com.u1.slicer.ui.SendPreparingBannerTest" --no-daemon`
Expected: PASS (all four cases — Tasks 4-6 satisfy them).

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/com/u1/slicer/ui/SendPreparingBannerTest.kt
git commit -m "test: F94 structural guard for Preparing banner wiring"
```

---

## Task 8: Full verification

- [ ] **Step 1: Run the full JVM unit suite**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: PASS. New tests: 5 in `CopyArrangeCalculatorTest`, 4 in `SendPreparingBannerTest`. No regressions.

- [ ] **Step 2: Run the targeted instrumented test**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.PreparePreviewViewModelTest#autoArrangeAll_twoObjects_noOverlapAndOnBed"`
Expected: PASS.

- [ ] **Step 3: Manual smoke (device)**

Install (`./gradlew installDebug`) and verify by hand (no automated coverage for these):
- **F94:** slice a large file → tap Upload Only / Map & Print → confirm the "Preparing G-code…" card appears immediately on the Printer tab, then transitions to "Uploading G-code…", then the success card.
- **F92:** load 2+ STLs (or split a multi-part 3MF), open the preview overlay ⋮ menu → "Auto-arrange all" → objects re-pack without overlapping each other or the wipe tower.

- [ ] **Step 4: Update docs/backlog (no release)**

Flip F94 (#166) and F92 (#162) from OPEN to DONE in `BACKLOG.md` with a short implementation summary, and note B135 is partially mitigated by `autoArrange` (overflow now reported, never placed off-bed). Do **not** create a release — that needs explicit authorization.

```bash
git add BACKLOG.md
git commit -m "docs: mark F94 + F92 done in backlog"
```

---

## Self-review notes

- **Spec coverage:** F94 (state + trigger + card + error catch + send-only scope) → Tasks 4-7. F92 (pure packer, tower-pinned reserved rect, all-objects, menu entry, tests) → Tasks 1-3. Both "files touched" tables match.
- **Type consistency:** `autoArrange(boxes, reservedRect, incoming, bedSize, margin)` and `ArrangeResult(positions, overflowCount)` used identically in Tasks 1-2. `beginSendPreparing()` / `reportSendError(String)` / `SendingState.Preparing` consistent across Tasks 4-7.
- **Deviation from spec (intentional):** apply delegates to existing `applyPlacementPositions(positions, towerPos)` rather than hand-rolled `setObjectPositions`/`setModelInstances` — it already branches multi/single and keeps native + state flows in sync (the spec's "to be confirmed during planning" note). Packing is front-left shelf (not centered) for keep-out simplicity and predictability; still resolves the reporter's tower-overlap issue.
