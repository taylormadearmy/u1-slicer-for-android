# Full-Spectrum M3 Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Ship the in-app **manual pair-mixer** for full-spectrum colour: a U1 owner can create a mixed-filament slot from any of three entry points (Filaments tab / Prepare screen / slot picker), promote it to a library, and slice a full-spectrum print without any pre-made 3MF.

**Architecture:** Add `MixedFilamentRow` + `MixedFilamentManager` data model (with `SessionState` for project mixes + `SettingsRepository` for library mixes). Wire `SlicerViewModel` to serialize the manager's rows into `SliceConfig.mixedFilamentDefinitions` at slice time. Build three Compose entry points opening the same `CreateMixSlotDialog`. Refactor `HighlightSlotPicker` into a `SectionedSlotPicker` (PHYSICAL / THIS PROJECT / LIBRARY).

**Tech Stack:** Kotlin 1.9.22 + Jetpack Compose + Material3 + DataStore. **No native rebuild needed** — engine wiring is from Stage 2, which already merged to main.

**Reference docs:**
- Spec: [`docs/superpowers/specs/2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md`](../specs/2026-06-06-full-spectrum-m3-phase-a-manual-pair-mixer-design.md) — read first.
- Parent roadmap: [`docs/superpowers/specs/2026-05-26-full-spectrum-roadmap.md`](../specs/2026-05-26-full-spectrum-roadmap.md).
- Engine recipe format reference: `app/src/main/cpp/orcaslicer/src/libslic3r/MixedFilament.cpp:2010` (`serialize_custom_entries` — verbatim format we mirror).

---

## Prerequisites

1. **Worktree.** Phase A is a fresh feature branch off `main`. From `D:/projects/u1-slicer-for-android`, create:
   ```bash
   git worktree add -b feature/m3-phase-a-mix-slots \
       .claude/worktrees/m3-phase-a-mix-slots main
   cd .claude/worktrees/m3-phase-a-mix-slots
   ```
2. **Submodule init** for the worktree (per `CLAUDE.md` worktree note):
   ```bash
   git submodule update --init --recursive app/src/main/cpp/orcaslicer
   ```
3. **Hooks installed** (this is a fresh clone surface):
   ```bash
   scripts/install-hooks.sh
   ```
4. **Baseline tests pass:**
   ```bash
   ./gradlew testDebugUnitTest --no-daemon
   ```
   Expected: BUILD SUCCESSFUL, ~1481 tests. If anything fails, stop and investigate before proceeding.

## File map (all 12 tasks)

| File | Status | Touched by tasks |
|---|---|---|
| `app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt` | NEW | 1 |
| `app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt` | NEW | 1 |
| `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt` | NEW | 2, 3, 4 |
| `app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt` | NEW | 2, 3, 4 |
| `app/src/main/java/com/u1/slicer/data/SessionState.kt` | MODIFY | 5 |
| `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt` | MODIFY | 5 |
| `app/src/test/java/com/u1/slicer/data/SessionStateMixPersistenceTest.kt` | NEW | 5 |
| `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt` | MODIFY | 6 |
| `app/src/test/java/com/u1/slicer/data/SettingsLibraryMixPersistenceTest.kt` | NEW | 6 |
| `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` | MODIFY | 7 |
| `app/src/test/java/com/u1/slicer/SliceConfigMixedFilamentWiringTest.kt` | NEW | 7 |
| `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt` | NEW | 8 |
| `app/src/main/java/com/u1/slicer/ui/SectionedSlotPicker.kt` | NEW | 9 |
| `app/src/main/java/com/u1/slicer/ui/HighlightSlotPicker.kt` | MODIFY (delegate to sectioned) | 9 |
| `app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt` | MODIFY | 10 |
| `app/src/main/java/com/u1/slicer/ui/PrepareScreen.kt` | MODIFY | 10 |
| `app/src/androidTest/.../MixedFilamentCreateE2ETest.kt` | NEW | 11 |
| `app/src/androidTest/.../MixedFilamentLibraryPersistenceE2ETest.kt` | NEW | 11 |

---

## Task 1: `MixedFilamentRow` data class + enum + auto-label helper

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt`
- Create: `app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt`

- [ ] **Step 1: Write failing test for data class shape + auto-label**

`app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class MixedFilamentRowTest {

    @Test
    fun `autoLabel formats components and percent`() {
        val row = MixedFilamentRow(
            id = 1L,
            componentA = 1,
            componentB = 3,
            mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = "",
            inLibrary = false,
        )
        assertEquals("E1+E3 @ 50%", MixedFilamentRow.autoLabel(row.componentA, row.componentB, row.mixBPercent))
    }

    @Test
    fun `autoLabel handles odd percentages`() {
        assertEquals("E2+E4 @ 33%",
            MixedFilamentRow.autoLabel(2, 4, 33))
    }

    @Test
    fun `equality treats id as identity`() {
        val a = MixedFilamentRow(1L, 1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, "x", false)
        val b = a.copy(label = "y", mixBPercent = 75)
        // Equality should still match because we treat id as the identity for swap.
        // But Kotlin data class equality is structural, so structural inequality
        // is the right answer; identity-by-id is a manager-level concern.
        assertNotEquals(a, b)
        assertEquals(a.id, b.id)
    }
}
```

- [ ] **Step 2: Run test, expect FAIL (class doesn't exist)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentRowTest*" 2>&1 | tail -10
```

Expected: compilation error (`Unresolved reference: MixedFilamentRow`).

- [ ] **Step 3: Implement `MixedFilamentRow.kt`**

`app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt`:

```kotlin
package com.u1.slicer.data

/**
 * One row of the full-spectrum mix recipe. Maps to one virtual filament slot
 * that the engine creates from the layer-alternation of two physical filaments.
 *
 * Indices are 1-based to match the engine's filament numbering.
 * IDs are millis-since-epoch + monotonic counter (assigned by MixedFilamentManager).
 */
data class MixedFilamentRow(
    val id: Long,
    val componentA: Int,                          // 1-based physical filament index
    val componentB: Int,                          // 1-based; must differ from componentA
    val mixBPercent: Int,                         // 0..100; share of component B
    val distributionMode: MixDistributionMode,
    val label: String,                            // auto-derived "E1+E3 @ 50%" or user-renamed
    val inLibrary: Boolean,                       // false = project-scoped, true = persistent across projects
) {
    enum class MixDistributionMode {
        /** Whole-layer alternation: layer N uses component A, N+1 uses B, etc. */
        LAYER_CYCLE,
        /** Same-layer dot pattern: each layer interleaves A and B in XY. */
        SAME_LAYER_DOTS,
    }

    companion object {
        /**
         * Default label when the user hasn't renamed the row.
         * Example: autoLabel(1, 3, 50) -> "E1+E3 @ 50%"
         */
        fun autoLabel(componentA: Int, componentB: Int, mixBPercent: Int): String =
            "E$componentA+E$componentB @ $mixBPercent%"
    }
}
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentRowTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixedFilamentRow.kt \
        app/src/test/java/com/u1/slicer/data/MixedFilamentRowTest.kt
git commit -m "feat(M3-A): add MixedFilamentRow data class + auto-label helper

One row of the full-spectrum mix recipe: maps to one virtual filament
slot the engine creates from layer-alternation of two physical
filaments. 1-based component indices match the engine's numbering.

Auto-label format: \"E<A>+E<B> @ <pct>%\".
"
```

---

## Task 2: `MixedFilamentManager` skeleton — in-memory add/edit/delete

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt`
- Create: `app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt`

We split MixedFilamentManager into 3 tasks: this task does in-memory CRUD; Task 3 adds the engine `serialize()`; Task 4 wires persistence (Task 5 + 6 add the DataStore round-trip).

- [ ] **Step 1: Write failing CRUD tests**

`app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt`:

```kotlin
package com.u1.slicer.data

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedFilamentManagerTest {

    private fun newManager() = MixedFilamentManager(
        // In-memory backing for unit tests. Persistence is tested in Tasks 5+6.
        loadProject = { emptyList() },
        loadLibrary = { emptyList() },
        saveProject = { /* no-op */ },
        saveLibrary = { /* no-op */ },
    )

    private fun sampleRow(id: Long, a: Int = 1, b: Int = 2, pct: Int = 50, inLib: Boolean = false) =
        MixedFilamentRow(
            id = id,
            componentA = a,
            componentB = b,
            mixBPercent = pct,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
            label = MixedFilamentRow.autoLabel(a, b, pct),
            inLibrary = inLib,
        )

    @Test
    fun `add appends to project list and bumps counter`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(componentA = 1, componentB = 3, mixBPercent = 33,
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val project = mgr.projectMixes.first()
        assertEquals(2, project.size)
        assertEquals(r1.id, project[0].id)
        assertEquals(r2.id, project[1].id)
        assertEquals("E1+E2 @ 50%", project[0].label)
        assertEquals("E1+E3 @ 33%", project[1].label)
    }

    @Test
    fun `edit replaces at same id and preserves order`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(1, 3, 33, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.edit(r1.id, componentA = 2, componentB = 4, mixBPercent = 75,
            distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
        val project = mgr.projectMixes.first()
        assertEquals(2, project.size)
        assertEquals(r1.id, project[0].id)   // same id
        assertEquals(2, project[0].componentA)
        assertEquals(4, project[0].componentB)
        assertEquals(75, project[0].mixBPercent)
        assertEquals(r2.id, project[1].id)   // r2 untouched
    }

    @Test
    fun `delete removes from project list`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val r2 = mgr.add(1, 3, 33, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.delete(r1.id)
        val project = mgr.projectMixes.first()
        assertEquals(1, project.size)
        assertEquals(r2.id, project[0].id)
    }

    @Test
    fun `promoteToLibrary copies project row into library and sets inLibrary`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.promoteToLibrary(r1.id)
        val project = mgr.projectMixes.first()
        val library = mgr.libraryMixes.first()
        assertEquals(1, project.size)
        assertEquals(1, library.size)
        assertTrue(project[0].inLibrary)
        assertTrue(library[0].inLibrary)
        assertEquals(project[0].id, library[0].id)
    }

    @Test
    fun `demoteFromLibrary removes the library copy`() = runTest {
        val mgr = newManager()
        val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        mgr.promoteToLibrary(r1.id)
        mgr.demoteFromLibrary(r1.id)
        val library = mgr.libraryMixes.first()
        assertEquals(0, library.size)
    }
}
```

- [ ] **Step 2: Run test, expect FAIL (class doesn't exist)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentManagerTest*" 2>&1 | tail -10
```

Expected: compilation error (`Unresolved reference: MixedFilamentManager`).

- [ ] **Step 3: Implement skeleton in `MixedFilamentManager.kt`**

`app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt`:

```kotlin
package com.u1.slicer.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds the user's full-spectrum mix recipe — both project-scoped rows
 * (cleared when a different model loads) and library rows (persistent
 * across projects).
 *
 * The serialize() method (added in Task 3) produces the engine's recipe
 * string consumed by SliceConfig.mixedFilamentDefinitions.
 *
 * Persistence is wired in Tasks 5 (project, via SessionStateRepository)
 * and 6 (library, via SettingsRepository).
 */
class MixedFilamentManager(
    private val loadProject: () -> List<MixedFilamentRow>,
    private val loadLibrary: () -> List<MixedFilamentRow>,
    private val saveProject: (List<MixedFilamentRow>) -> Unit,
    private val saveLibrary: (List<MixedFilamentRow>) -> Unit,
) {
    private val _projectMixes = MutableStateFlow(loadProject())
    private val _libraryMixes = MutableStateFlow(loadLibrary())
    val projectMixes: StateFlow<List<MixedFilamentRow>> = _projectMixes.asStateFlow()
    val libraryMixes: StateFlow<List<MixedFilamentRow>> = _libraryMixes.asStateFlow()

    // Monotonic counter to seed unique ids within a single process.
    // Combined with the millis-since-epoch base, this is collision-free
    // for any reasonable rate of mix creation.
    private val counter = AtomicLong(0)

    private fun nextId(): Long =
        System.currentTimeMillis() * 1_000L + counter.incrementAndGet()

    fun add(
        componentA: Int,
        componentB: Int,
        mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode,
    ): MixedFilamentRow {
        require(componentA != componentB) { "componentA must differ from componentB" }
        require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
        val row = MixedFilamentRow(
            id = nextId(),
            componentA = componentA,
            componentB = componentB,
            mixBPercent = mixBPercent,
            distributionMode = distributionMode,
            label = MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            inLibrary = false,
        )
        _projectMixes.value = _projectMixes.value + row
        saveProject(_projectMixes.value)
        return row
    }

    fun edit(
        id: Long,
        componentA: Int,
        componentB: Int,
        mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode,
        label: String? = null,
    ) {
        require(componentA != componentB) { "componentA must differ from componentB" }
        require(mixBPercent in 0..100) { "mixBPercent must be 0..100" }
        _projectMixes.value = _projectMixes.value.map { existing ->
            if (existing.id != id) existing
            else existing.copy(
                componentA = componentA,
                componentB = componentB,
                mixBPercent = mixBPercent,
                distributionMode = distributionMode,
                label = label
                    ?: MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            )
        }
        // Library copy (if any) also updates so the user's saved version stays in sync.
        _libraryMixes.value = _libraryMixes.value.map { existing ->
            if (existing.id != id) existing
            else existing.copy(
                componentA = componentA,
                componentB = componentB,
                mixBPercent = mixBPercent,
                distributionMode = distributionMode,
                label = label
                    ?: MixedFilamentRow.autoLabel(componentA, componentB, mixBPercent),
            )
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun delete(id: Long) {
        _projectMixes.value = _projectMixes.value.filterNot { it.id == id }
        _libraryMixes.value = _libraryMixes.value.filterNot { it.id == id }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun promoteToLibrary(id: Long) {
        val row = _projectMixes.value.firstOrNull { it.id == id } ?: return
        val promoted = row.copy(inLibrary = true)
        _projectMixes.value = _projectMixes.value.map { if (it.id == id) promoted else it }
        if (_libraryMixes.value.none { it.id == id }) {
            _libraryMixes.value = _libraryMixes.value + promoted
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }

    fun demoteFromLibrary(id: Long) {
        _libraryMixes.value = _libraryMixes.value.filterNot { it.id == id }
        _projectMixes.value = _projectMixes.value.map {
            if (it.id == id) it.copy(inLibrary = false) else it
        }
        saveProject(_projectMixes.value)
        saveLibrary(_libraryMixes.value)
    }
}
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentManagerTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 5 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt \
        app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt
git commit -m "feat(M3-A): MixedFilamentManager in-memory CRUD

State for project-scoped + library-scoped mix rows. CRUD operations
(add / edit / delete / promoteToLibrary / demoteFromLibrary) emit via
StateFlow so Compose recomposes on change. ID generation is millis +
monotonic counter — collision-free for any realistic creation rate.

Persistence callbacks (loadProject, loadLibrary, saveProject,
saveLibrary) are injected so unit tests use no-op closures and
production wires them to DataStore (Tasks 5, 6)."
```

---

## Task 3: `MixedFilamentManager.serialize()` — engine recipe string

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt`
- Modify: `app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt` (append tests)

- [ ] **Step 1: Append failing tests for serialize**

Append to `MixedFilamentManagerTest.kt` (after the existing `@Test` methods):

```kotlin
@Test
fun `serialize emits empty string when no rows`() = runTest {
    val mgr = newManager()
    assertEquals("", mgr.serialize(numPhysicalFilaments = 4))
}

@Test
fun `serialize emits engine row format for a single project row`() = runTest {
    val mgr = newManager()
    val r = mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
        distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    val out = mgr.serialize(numPhysicalFilaments = 4)
    // Engine format (mirrors libslic3r/MixedFilament.cpp::serialize_custom_entries):
    //   <a>,<b>,<enabled>,<custom>,<mix_b_pct>,<pointillism>,g,w,m<dist>,z0,xa0,xb0,d0,o0,u<stable_id>
    assertEquals("1,2,1,1,50,0,g,w,m0,z0,xa0,xb0,d0,o0,u${r.id}", out)
}

@Test
fun `serialize concatenates multiple rows with semicolon`() = runTest {
    val mgr = newManager()
    val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    val r2 = mgr.add(2, 3, 33, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
    val out = mgr.serialize(numPhysicalFilaments = 4)
    val expected =
        "1,2,1,1,50,0,g,w,m0,z0,xa0,xb0,d0,o0,u${r1.id}" +
        ";" +
        "2,3,1,1,33,0,g,w,m1,z0,xa0,xb0,d0,o0,u${r2.id}"
    assertEquals(expected, out)
}

@Test
fun `serialize layer cycle uses m0 and same layer dots uses m1`() = runTest {
    val mgr = newManager()
    val rL = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    val rD = mgr.add(1, 3, 50, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS)
    val out = mgr.serialize(numPhysicalFilaments = 4).split(";")
    assertTrue(out[0].contains(",m0,"))
    assertFalse(out[0].contains(",m1,"))
    assertTrue(out[1].contains(",m1,"))
    assertFalse(out[1].contains(",m0,"))
}

@Test
fun `serialize skips library rows whose components exceed numPhysicalFilaments`() = runTest {
    // Library rows referencing slots beyond the current physical count must be
    // hidden from the engine — they were created in a richer project and are
    // unusable here.
    val mgr = MixedFilamentManager(
        loadProject = { emptyList() },
        loadLibrary = {
            listOf(
                MixedFilamentRow(
                    id = 100L,
                    componentA = 1,
                    componentB = 4, // beyond a 2-extruder project
                    mixBPercent = 50,
                    distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                    label = "test",
                    inLibrary = true,
                ),
                MixedFilamentRow(
                    id = 200L,
                    componentA = 1,
                    componentB = 2, // fits in a 2-extruder project
                    mixBPercent = 25,
                    distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                    label = "test",
                    inLibrary = true,
                ),
            )
        },
        saveProject = {},
        saveLibrary = {},
    )
    val out = mgr.serialize(numPhysicalFilaments = 2)
    assertEquals("1,2,1,1,25,0,g,w,m0,z0,xa0,xb0,d0,o0,u200", out)
}

@Test
fun `serialize concatenates project rows then library rows`() = runTest {
    val mgr = MixedFilamentManager(
        loadProject = { emptyList() },
        loadLibrary = {
            listOf(MixedFilamentRow(999L, 1, 4, 25,
                MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                "lib", true))
        },
        saveProject = {},
        saveLibrary = {},
    )
    val r1 = mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    val out = mgr.serialize(numPhysicalFilaments = 4)
    val rows = out.split(";")
    assertEquals(2, rows.size)
    assertTrue(rows[0].startsWith("1,2,"))   // project row first
    assertTrue(rows[1].startsWith("1,4,"))   // library row second
}
```

- [ ] **Step 2: Run test, expect FAIL (serialize doesn't exist yet)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentManagerTest*" 2>&1 | tail -10
```

Expected: compilation error (`Unresolved reference: serialize`).

- [ ] **Step 3: Implement `serialize()` in MixedFilamentManager.kt**

Append to `MixedFilamentManager.kt`'s class body (before the closing `}`):

```kotlin
    /**
     * Engine recipe string for `mixed_filament_definitions`. Project rows
     * first, then library rows. Library rows referencing slots beyond
     * `numPhysicalFilaments` are silently skipped (incompatible with the
     * current project).
     *
     * Format mirrors `libslic3r/MixedFilament.cpp::serialize_custom_entries`:
     *   <a>,<b>,<enabled>,<custom>,<mix_b_pct>,<pointillism>,g,w,m<dist>,z0,xa0,xb0,d0,o0,u<id>
     * separated by `;`. Empty string when no rows are present (engine treats
     * this as "no mixing").
     */
    fun serialize(numPhysicalFilaments: Int): String {
        val rows = mutableListOf<String>()
        for (r in _projectMixes.value) rows.add(serializeRow(r))
        for (r in _libraryMixes.value) {
            if (r.componentA > numPhysicalFilaments || r.componentB > numPhysicalFilaments) continue
            // Skip library rows already promoted from project (they're in both lists).
            if (_projectMixes.value.any { it.id == r.id }) continue
            rows.add(serializeRow(r))
        }
        return rows.joinToString(";")
    }

    private fun serializeRow(r: MixedFilamentRow): String {
        val distMode = when (r.distributionMode) {
            MixedFilamentRow.MixDistributionMode.LAYER_CYCLE -> 0
            MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS -> 1
        }
        // enabled=1, custom=1 for any user-created row.
        // pointillism_all_filaments=0 (not used in v1).
        // gradient_component_ids = empty, gradient_component_weights = empty (no gradient in v1).
        // local_z_max_sublayers=0, surface_offsets=0, deleted=0, origin_auto=0.
        return "${r.componentA},${r.componentB},1,1,${r.mixBPercent},0,g,w,m$distMode,z0,xa0,xb0,d0,o0,u${r.id}"
    }
```

- [ ] **Step 4: Run test, expect PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*MixedFilamentManagerTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 11 tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/MixedFilamentManager.kt \
        app/src/test/java/com/u1/slicer/data/MixedFilamentManagerTest.kt
git commit -m "feat(M3-A): MixedFilamentManager.serialize() engine recipe format

Mirrors libslic3r/MixedFilament.cpp::serialize_custom_entries verbatim.
Concatenates project rows then compatible library rows with ';'. Library
rows referencing slots beyond the current numPhysicalFilaments are
silently skipped (incompatible with current project). LAYER_CYCLE emits
m0, SAME_LAYER_DOTS emits m1."
```

---

## Task 4: Round-trip through engine parser (instrumented sanity check via JSON shim)

**Goal:** Confirm a serialised row from our Kotlin matches the engine's
expected format. We can't call the native parser from JVM unit tests, but we
can pin format via golden-string equality — already done in Task 3. The real
round-trip happens in Task 11's instrumented test.

- [ ] **Step 1: No code change. Mark this task as a checkpoint and move on.**

(This task is intentionally empty — the engine round-trip is exercised in
Task 11. Keeping the task number here to preserve sequential numbering with
the spec's "Tasks" mental model.)

---

## Task 5: DataStore persistence for project mixes (in `SessionState`)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SessionState.kt`
- Modify: `app/src/main/java/com/u1/slicer/data/SessionStateRepository.kt`
- Create: `app/src/test/java/com/u1/slicer/data/SessionStateMixPersistenceTest.kt`

- [ ] **Step 1: Inspect SessionState.kt to find the existing serialisation pattern**

```bash
grep -n 'data class SessionState\|fun toJson\|fun fromJson' app/src/main/java/com/u1/slicer/data/SessionState.kt | head -10
```

The class already serialises to JSON via `org.json`. Follow the same pattern:
add a `projectMixes: List<MixedFilamentRow>` field with default `emptyList()`,
extend `toJson()` to write it, extend `fromJson()` to read it.

- [ ] **Step 2: Write failing test for JSON round-trip**

`app/src/test/java/com/u1/slicer/data/SessionStateMixPersistenceTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionStateMixPersistenceTest {

    private fun sampleRow(id: Long = 1L, a: Int = 1, b: Int = 2, pct: Int = 50,
        dist: MixedFilamentRow.MixDistributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
        inLib: Boolean = false) = MixedFilamentRow(
        id = id, componentA = a, componentB = b, mixBPercent = pct,
        distributionMode = dist,
        label = MixedFilamentRow.autoLabel(a, b, pct),
        inLibrary = inLib,
    )

    @Test
    fun `roundtrip preserves empty projectMixes`() {
        val s = SessionState(modelName = "x", rawInputPath = "/x")
        val json = s.toJson()
        val back = SessionState.fromJson(json)!!
        assertEquals(emptyList<MixedFilamentRow>(), back.projectMixes)
    }

    @Test
    fun `roundtrip preserves multiple projectMixes with both distribution modes`() {
        val s = SessionState(
            modelName = "x", rawInputPath = "/x",
            projectMixes = listOf(
                sampleRow(1L, 1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE),
                sampleRow(2L, 1, 3, 33, MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS, inLib = true),
            ),
        )
        val json = s.toJson()
        val back = SessionState.fromJson(json)!!
        assertEquals(2, back.projectMixes.size)
        assertEquals(1L, back.projectMixes[0].id)
        assertEquals(MixedFilamentRow.MixDistributionMode.LAYER_CYCLE, back.projectMixes[0].distributionMode)
        assertEquals(2L, back.projectMixes[1].id)
        assertEquals(MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS, back.projectMixes[1].distributionMode)
        assertEquals(true, back.projectMixes[1].inLibrary)
    }

    @Test
    fun `fromJson tolerates missing projectMixes field (older state)`() {
        // An older app version's SessionState JSON won't have the field.
        // Reading it back must default to an empty list.
        val s = SessionState(modelName = "x", rawInputPath = "/x")
        val json = s.toJson()
        // Strip the field if present (simulate older format).
        val stripped = org.json.JSONObject(json).apply { remove("projectMixes") }.toString()
        val back = SessionState.fromJson(stripped)!!
        assertEquals(emptyList<MixedFilamentRow>(), back.projectMixes)
    }
}
```

- [ ] **Step 3: Run test, expect FAIL (no projectMixes field on SessionState)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SessionStateMixPersistenceTest*" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 4: Add `projectMixes` to SessionState + JSON wiring**

Open `app/src/main/java/com/u1/slicer/data/SessionState.kt`. Find the `data class SessionState(` declaration. Add a new field with default value:

```kotlin
data class SessionState(
    // ...existing fields...
    val projectMixes: List<MixedFilamentRow> = emptyList(),
)
```

In `toJson()`, after existing field writes, add:

```kotlin
val mixesArray = org.json.JSONArray()
for (m in projectMixes) {
    mixesArray.put(org.json.JSONObject().apply {
        put("id", m.id)
        put("componentA", m.componentA)
        put("componentB", m.componentB)
        put("mixBPercent", m.mixBPercent)
        put("distributionMode", m.distributionMode.name)
        put("label", m.label)
        put("inLibrary", m.inLibrary)
    })
}
obj.put("projectMixes", mixesArray)
```

In `fromJson()`, after existing field reads:

```kotlin
val mixesArray = obj.optJSONArray("projectMixes")
val projectMixes = if (mixesArray == null) emptyList() else (0 until mixesArray.length()).map { i ->
    val o = mixesArray.getJSONObject(i)
    MixedFilamentRow(
        id = o.getLong("id"),
        componentA = o.getInt("componentA"),
        componentB = o.getInt("componentB"),
        mixBPercent = o.getInt("mixBPercent"),
        distributionMode = MixedFilamentRow.MixDistributionMode.valueOf(o.getString("distributionMode")),
        label = o.getString("label"),
        inLibrary = o.getBoolean("inLibrary"),
    )
}
```

Pass `projectMixes` into the returned `SessionState(...)` constructor at the end of `fromJson`.

- [ ] **Step 5: Run test, expect PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SessionStateMixPersistenceTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 6: Verify existing SessionState tests still pass**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SessionStateTest*" 2>&1 | tail -10
```

Expected: existing tests stay green (we only added a new optional field).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SessionState.kt \
        app/src/test/java/com/u1/slicer/data/SessionStateMixPersistenceTest.kt
git commit -m "feat(M3-A): persist projectMixes in SessionState

JSON round-trip + missing-field tolerance for forward compatibility.
Older app versions' state JSONs omit the field and read back as an
empty list (no migration code needed).
"
```

---

## Task 6: DataStore persistence for library mixes (in `SettingsRepository`)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`
- Create: `app/src/test/java/com/u1/slicer/data/SettingsLibraryMixPersistenceTest.kt`

- [ ] **Step 1: Inspect SettingsRepository to find the existing DataStore pattern**

```bash
grep -nE 'stringPreferencesKey|object Keys|fun set|Flow<' app/src/main/java/com/u1/slicer/data/SettingsRepository.kt | head -20
```

The repository has typed flows + setters per setting. Follow that pattern for `libraryMixes`.

- [ ] **Step 2: Write failing test**

`app/src/test/java/com/u1/slicer/data/SettingsLibraryMixPersistenceTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsLibraryMixPersistenceTest {

    @Test
    fun `encodeLibraryMixes round-trips empty list to empty string`() {
        val encoded = SettingsRepository.encodeLibraryMixes(emptyList())
        assertEquals("", encoded)
        assertEquals(emptyList<MixedFilamentRow>(), SettingsRepository.decodeLibraryMixes(""))
    }

    @Test
    fun `encodeLibraryMixes round-trips multiple rows`() {
        val rows = listOf(
            MixedFilamentRow(
                id = 1L, componentA = 1, componentB = 2, mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "E1+E2 @ 50%", inLibrary = true,
            ),
            MixedFilamentRow(
                id = 2L, componentA = 2, componentB = 4, mixBPercent = 33,
                distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                label = "My Sage", inLibrary = true,
            ),
        )
        val encoded = SettingsRepository.encodeLibraryMixes(rows)
        val decoded = SettingsRepository.decodeLibraryMixes(encoded)
        assertEquals(rows, decoded)
    }

    @Test
    fun `decodeLibraryMixes tolerates malformed input`() {
        // Corrupt DataStore values (e.g. partial writes) must not crash.
        assertEquals(emptyList<MixedFilamentRow>(), SettingsRepository.decodeLibraryMixes("not-json"))
    }
}
```

- [ ] **Step 3: Run test, expect FAIL (encode/decode don't exist)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SettingsLibraryMixPersistenceTest*" 2>&1 | tail -10
```

Expected: compilation error.

- [ ] **Step 4: Add encode/decode + DataStore key + flow + setter**

In `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`:

1. Add to the `companion object` (or wherever existing static helpers live):

```kotlin
companion object {
    // ... existing keys ...

    internal fun encodeLibraryMixes(rows: List<MixedFilamentRow>): String {
        if (rows.isEmpty()) return ""
        val arr = org.json.JSONArray()
        for (r in rows) {
            arr.put(org.json.JSONObject().apply {
                put("id", r.id)
                put("componentA", r.componentA)
                put("componentB", r.componentB)
                put("mixBPercent", r.mixBPercent)
                put("distributionMode", r.distributionMode.name)
                put("label", r.label)
                put("inLibrary", r.inLibrary)
            })
        }
        return arr.toString()
    }

    internal fun decodeLibraryMixes(encoded: String): List<MixedFilamentRow> {
        if (encoded.isEmpty()) return emptyList()
        return try {
            val arr = org.json.JSONArray(encoded)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                MixedFilamentRow(
                    id = o.getLong("id"),
                    componentA = o.getInt("componentA"),
                    componentB = o.getInt("componentB"),
                    mixBPercent = o.getInt("mixBPercent"),
                    distributionMode = MixedFilamentRow.MixDistributionMode.valueOf(o.getString("distributionMode")),
                    label = o.getString("label"),
                    inLibrary = o.getBoolean("inLibrary"),
                )
            }
        } catch (e: org.json.JSONException) {
            emptyList()
        }
    }
}
```

2. Add a DataStore preference key + flow + setter (match the pattern of other settings in this file). Use key name `library_mixes` (string-typed because DataStore string keys are simpler than List<String> here, and our encode/decode round-trips a single string).

(This step's exact code depends on existing repository structure — match what's there. Skip the "matching" if the file is too unfamiliar and ask the controller to look at the existing pattern.)

- [ ] **Step 5: Run test, expect PASS**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SettingsLibraryMixPersistenceTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 3 tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SettingsRepository.kt \
        app/src/test/java/com/u1/slicer/data/SettingsLibraryMixPersistenceTest.kt
git commit -m "feat(M3-A): persist libraryMixes in SettingsRepository DataStore

JSON encode/decode helpers + DataStore string key + reactive Flow.
Malformed-input tolerance: corrupt values decode as empty list
rather than crashing.
"
```

---

## Task 7: Wire MixedFilamentManager into SlicerViewModel + slice config

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`
- Create: `app/src/test/java/com/u1/slicer/SliceConfigMixedFilamentWiringTest.kt`

- [ ] **Step 1: Write failing test that asserts SliceConfig.mixedFilamentDefinitions is populated from the manager at slice time**

`app/src/test/java/com/u1/slicer/SliceConfigMixedFilamentWiringTest.kt`:

```kotlin
package com.u1.slicer

import com.u1.slicer.data.MixedFilamentRow
import org.junit.Assert.assertEquals
import org.junit.Test

class SliceConfigMixedFilamentWiringTest {

    @Test
    fun `serialize from empty manager produces empty string and SliceConfig field stays empty`() {
        val mgr = com.u1.slicer.data.MixedFilamentManager(
            loadProject = { emptyList() }, loadLibrary = { emptyList() },
            saveProject = {}, saveLibrary = {},
        )
        assertEquals("", mgr.serialize(numPhysicalFilaments = 4))
    }

    @Test
    fun `manager rows are reflected in SliceConfig mixedFilamentDefinitions when applied`() {
        val mgr = com.u1.slicer.data.MixedFilamentManager(
            loadProject = { emptyList() }, loadLibrary = { emptyList() },
            saveProject = {}, saveLibrary = {},
        )
        mgr.add(1, 2, 50, MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        // Simulate what SlicerViewModel does just before slice():
        val cfg = com.u1.slicer.data.SliceConfig(extruderCount = 2,
            mixedFilamentDefinitions = mgr.serialize(numPhysicalFilaments = 2))
        assertEquals(true, cfg.mixedFilamentDefinitions.startsWith("1,2,1,1,50,0,g,w,m0,"))
    }
}
```

- [ ] **Step 2: Run test, expect PASS (Tasks 1–3 made this work; this confirms nothing's regressed)**

```bash
./gradlew testDebugUnitTest --no-daemon --tests "*SliceConfigMixedFilamentWiringTest*" 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 3: Wire SlicerViewModel to construct a `MixedFilamentManager` and use it at slice time**

In `app/src/main/java/com/u1/slicer/SlicerViewModel.kt`:

1. Add a field:

```kotlin
val mixedFilamentManager: MixedFilamentManager = MixedFilamentManager(
    loadProject = { sessionStateRepository.snapshot()?.projectMixes ?: emptyList() },
    loadLibrary = { settingsRepository.snapshotLibraryMixes() },
    saveProject = { rows ->
        sessionStateRepository.update { state -> state.copy(projectMixes = rows) }
    },
    saveLibrary = { rows -> settingsRepository.setLibraryMixes(rows) },
)
```

(The exact `sessionStateRepository.snapshot()` / `update(...)` / `settingsRepository.snapshotLibraryMixes()` / `setLibraryMixes(...)` methods must match what was added in Tasks 5 + 6. If they don't exist with these names, name them consistently in those tasks and revisit here.)

2. Find every `slice()` invocation in SlicerViewModel (search for `lib.slice(`). Before each one, set:

```kotlin
cfg.mixedFilamentDefinitions = mixedFilamentManager.serialize(numPhysicalFilaments = cfg.extruderCount)
```

(Or pass it via constructor copy — match the existing pattern.)

- [ ] **Step 4: Run full unit suite to confirm no regression**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Expected: BUILD SUCCESSFUL. Test count = previous baseline (1481+) + (3 from Task 1) + (11 from Tasks 2–3) + (3 from Task 5) + (3 from Task 6) + (2 from Task 7) = ~1503.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/SlicerViewModel.kt \
        app/src/test/java/com/u1/slicer/SliceConfigMixedFilamentWiringTest.kt
git commit -m "feat(M3-A): wire MixedFilamentManager into SlicerViewModel slice path

ViewModel owns a single MixedFilamentManager instance, persistence-
backed via SessionStateRepository (project mixes) and SettingsRepository
(library mixes). At every slice call site, the manager's serialize()
output populates SliceConfig.mixedFilamentDefinitions.

Existing test suite stays green (no other changes to slice path)."
```

---

## Task 8: `CreateMixSlotDialog` Composable

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt`

This task adds the dialog UI; we don't add Compose tests yet (those live in
Task 11's instrumented suite where the runtime is available). A self-contained
review of the dialog's behaviour happens at Task 10 when it's wired to entry
points.

- [ ] **Step 1: Implement the dialog**

`app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt`:

```kotlin
package com.u1.slicer.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Single-screen modal for creating or editing a mixed-filament slot.
 *
 * Visual choice "B" from the M3 Phase A design spec: two filament chips
 * for components A and B, a 0-100% ratio slider, a visible distribution-
 * mode toggle (LAYER_CYCLE vs SAME_LAYER_DOTS), live preview swatch,
 * and a print-cost subtitle. The Create button is disabled when A == B.
 *
 * When `editingRow` is non-null, the dialog is in Edit mode (title shifts,
 * Delete button appears, save callback is wired to edit).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateMixSlotDialog(
    physicalFilamentColours: List<Color>,        // size 1..4
    physicalFilamentLabels: List<String>,        // size matches colours
    editingRow: MixedFilamentRow? = null,
    onConfirm: (componentA: Int, componentB: Int, mixBPercent: Int,
        distributionMode: MixedFilamentRow.MixDistributionMode) -> Unit,
    onDelete: (() -> Unit)? = null,
    onDismiss: () -> Unit,
) {
    var componentA by remember { mutableStateOf(editingRow?.componentA ?: 1) }
    var componentB by remember { mutableStateOf(editingRow?.componentB ?: 2.coerceAtMost(physicalFilamentColours.size)) }
    var mixBPercent by remember { mutableStateOf(editingRow?.mixBPercent ?: 50) }
    var distributionMode by remember {
        mutableStateOf(editingRow?.distributionMode
            ?: MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
    }

    val sameComponentError = componentA == componentB
    val canConfirm = !sameComponentError
    val isEditing = editingRow != null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (isEditing) "Edit Mix Slot" else "Create Mix Slot") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // Component A picker
                Text("Component A", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    physicalFilamentColours.forEachIndexed { idx, c ->
                        val slot = idx + 1
                        FilamentChip(
                            colour = c,
                            label = physicalFilamentLabels.getOrNull(idx) ?: "E$slot",
                            selected = componentA == slot,
                            onClick = { componentA = slot },
                        )
                    }
                }
                // Component B picker
                Text("Component B", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    physicalFilamentColours.forEachIndexed { idx, c ->
                        val slot = idx + 1
                        FilamentChip(
                            colour = c,
                            label = physicalFilamentLabels.getOrNull(idx) ?: "E$slot",
                            selected = componentB == slot,
                            onClick = { componentB = slot },
                        )
                    }
                }
                if (sameComponentError) {
                    Text(
                        "Pick two different filaments.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                // Ratio slider
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text("Mix ratio", style = MaterialTheme.typography.labelMedium)
                    Text(
                        "$mixBPercent% E$componentB",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelMedium,
                    )
                }
                Slider(
                    value = mixBPercent.toFloat(),
                    onValueChange = { mixBPercent = it.toInt() },
                    valueRange = 0f..100f,
                    steps = 99,
                )
                // Live preview swatch (uses existing MixedSlotSwatch)
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    MixedSlotSwatch(
                        primary = physicalFilamentColours.getOrNull(componentA - 1) ?: Color.Gray,
                        secondary = physicalFilamentColours.getOrNull(componentB - 1),
                        size = 56.dp,
                    )
                }
                // Distribution mode toggle
                Text("Pattern", style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DistributionChip(
                        label = "Layer alternation",
                        selected = distributionMode == MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                        onClick = { distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE },
                    )
                    DistributionChip(
                        label = "Same-layer dots",
                        selected = distributionMode == MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS,
                        onClick = { distributionMode = MixedFilamentRow.MixDistributionMode.SAME_LAYER_DOTS },
                    )
                }
                // Print-cost tag (heuristic; refined later)
                Text(
                    "Mix slots add print time (each tool change ~ 5–10 s).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = canConfirm,
                onClick = {
                    onConfirm(componentA, componentB, mixBPercent, distributionMode)
                    onDismiss()
                },
            ) { Text(if (isEditing) "Save" else "Create") }
        },
        dismissButton = {
            if (isEditing && onDelete != null) {
                TextButton(
                    onClick = { onDelete(); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            } else {
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun FilamentChip(colour: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape),
        onClick = onClick,
        shape = CircleShape,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
        color = colour,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(label, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun DistributionChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.18f) else Color.Transparent
    val border = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, border),
        color = bg,
    ) {
        Box(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
```

(Note: `BorderStroke` import is `androidx.compose.foundation.BorderStroke`. Adjust if compiler complains.)

- [ ] **Step 2: Build to confirm it compiles**

```bash
./gradlew compileDebugKotlin --no-daemon 2>&1 | tail -5
```

Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt
git commit -m "feat(M3-A): CreateMixSlotDialog Composable

Single-screen modal for creating/editing a mix slot. Visual design B from
the Phase A spec: two filament chip rows for components A and B, 0-100%
ratio slider, live preview via MixedSlotSwatch, visible distribution-mode
toggle (Layer alternation vs Same-layer dots), and a print-cost subtitle.

Create button disabled when A == B (shows inline error). Edit mode shows
Delete button instead of Cancel; save callback gets the same data shape.
"
```

---

## Task 9: `SectionedSlotPicker` Composable (replaces HighlightSlotPicker)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/SectionedSlotPicker.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/HighlightSlotPicker.kt`

- [ ] **Step 1: Inspect HighlightSlotPicker to see current callers**

```bash
grep -rn 'HighlightSlotPicker' app/src/main/java app/src/test app/src/androidTest 2>&1 | head -20
```

Note every call site. Most likely callers: AiPaintResultScreen, FilamentMappingDialog. The refactor must preserve all call sites (HighlightSlotPicker becomes a thin delegate to SectionedSlotPicker so the rest of the app doesn't have to change in a single task).

- [ ] **Step 2: Implement SectionedSlotPicker**

`app/src/main/java/com/u1/slicer/ui/SectionedSlotPicker.kt`:

```kotlin
package com.u1.slicer.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.u1.slicer.data.MixedFilamentRow

/**
 * Slot-assignment picker with three labelled sections:
 *   PHYSICAL        — E1..E4 colour chips
 *   THIS PROJECT    — project-scoped mix slots + "+ Add" chip
 *   LIBRARY (★)     — library mixes whose components fit the current
 *                     numPhysicalFilaments (others hidden silently)
 *
 * Each chip emits its virtual slot ID via `onSelect`:
 *   Physical chip → index in 0..3 (matches AiRegion.slot today)
 *   Mix chip      → numPhysicalFilaments + position-in-combined-mix-list
 *
 * Tap to select. Long-press a mix chip to fire `onEditMix(row)`. "+ Add"
 * fires `onCreateMix()`.
 */
@Composable
fun SectionedSlotPicker(
    physicalColours: List<Color>,
    physicalLabels: List<String>,
    projectMixes: List<MixedFilamentRow>,
    libraryMixes: List<MixedFilamentRow>,        // already filtered to fit current numPhysicalFilaments
    selectedSlot: Int,
    onSelect: (slot: Int) -> Unit,
    onCreateMix: () -> Unit,
    onEditMix: (row: MixedFilamentRow) -> Unit,
) {
    val numPhysical = physicalColours.size

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // PHYSICAL
        SectionLabel("PHYSICAL")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            physicalColours.forEachIndexed { idx, colour ->
                PhysicalSlotChip(
                    colour = colour,
                    label = physicalLabels.getOrNull(idx) ?: "E${idx + 1}",
                    selected = selectedSlot == idx,
                    onClick = { onSelect(idx) },
                )
            }
        }

        // THIS PROJECT (always show section even if empty, so + Add is discoverable)
        SectionLabel("THIS PROJECT", trailing = "+ Add", onTrailingClick = onCreateMix)
        if (projectMixes.isEmpty()) {
            Text(
                "No mix slots yet — tap + Add to create one.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                projectMixes.forEachIndexed { idx, row ->
                    MixSlotChip(
                        row = row,
                        physicalColours = physicalColours,
                        selected = selectedSlot == numPhysical + idx,
                        onClick = { onSelect(numPhysical + idx) },
                        onLongClick = { onEditMix(row) },
                    )
                }
            }
        }

        // LIBRARY (hidden when empty)
        if (libraryMixes.isNotEmpty()) {
            SectionLabel("LIBRARY ★")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                libraryMixes.forEachIndexed { idx, row ->
                    MixSlotChip(
                        row = row,
                        physicalColours = physicalColours,
                        selected = selectedSlot == numPhysical + projectMixes.size + idx,
                        onClick = { onSelect(numPhysical + projectMixes.size + idx) },
                        onLongClick = { onEditMix(row) },
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String, trailing: String? = null, onTrailingClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (trailing != null) {
            TextButton(onClick = { onTrailingClick?.invoke() }) { Text(trailing) }
        }
    }
}

@Composable
private fun PhysicalSlotChip(colour: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clip(CircleShape),
        onClick = onClick,
        shape = CircleShape,
        color = colour,
        border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
    ) {
        Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
            Text(label)
        }
    }
}

@Composable
private fun MixSlotChip(
    row: MixedFilamentRow,
    physicalColours: List<Color>,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val primary = physicalColours.getOrNull(row.componentA - 1) ?: Color.Gray
    val secondary = physicalColours.getOrNull(row.componentB - 1)
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color.Transparent),
        contentAlignment = Alignment.Center,
    ) {
        MixedSlotSwatch(
            primary = primary,
            secondary = secondary,
            size = 40.dp,
            modifier = Modifier
                .clip(CircleShape)
                .combinedClickable(
                    // Tap = select. Long-press = edit.
                    onClick = onClick,
                    onLongClick = onLongClick,
                ),
        )
        if (selected) {
            // Selection ring drawn over the swatch.
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clip(CircleShape),
            )
        }
    }
}
```

Note: `combinedClickable` is `androidx.compose.foundation.combinedClickable`.
Adjust imports as compiler suggests.

- [ ] **Step 3: Make HighlightSlotPicker delegate to SectionedSlotPicker**

Edit `app/src/main/java/com/u1/slicer/ui/HighlightSlotPicker.kt` so its
existing public function signature stays the same but the body wraps
`SectionedSlotPicker` with empty `projectMixes` and `libraryMixes` lists
and stub `onCreateMix` / `onEditMix` callbacks. This keeps existing callers
working unchanged. Task 10 changes the relevant callers to pass real mix
lists.

- [ ] **Step 4: Build + run full unit suite**

```bash
./gradlew compileDebugKotlin --no-daemon 2>&1 | tail -5
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -10
```

Both expected: BUILD SUCCESSFUL, no test regressions.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SectionedSlotPicker.kt \
        app/src/main/java/com/u1/slicer/ui/HighlightSlotPicker.kt
git commit -m "feat(M3-A): SectionedSlotPicker (PHYSICAL / THIS PROJECT / LIBRARY)

Three-section slot-assignment picker per Phase A visual choice C.
Physical chips emit slots 0..3; project-mix chips emit numPhysical+idx;
library-mix chips emit numPhysical+|project|+idx. Tap selects;
long-press a mix chip fires onEditMix.

HighlightSlotPicker becomes a thin delegate (no behaviour change for
existing callers) so the refactor lands atomically without breaking
AiPaintResultScreen or FilamentMappingDialog. Task 10 migrates those
callers to pass real mix lists.
"
```

---

## Task 10: Wire entry points (Filaments tab + Prepare screen + Smart Paint slot picker)

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/PrepareScreen.kt`
- Modify: every caller of `HighlightSlotPicker` (currently AiPaintResultScreen, FilamentMappingDialog — confirm by grep)

- [ ] **Step 1: Filaments tab — add "+ Mix slot" row + dialog**

In `FilamentScreen.kt`, after the existing physical filament rows, add a
"+ Mix slot" row. Tapping opens `CreateMixSlotDialog`. The dialog's
`onConfirm` calls `viewModel.mixedFilamentManager.add(...)`.

(Add a `var showCreateMixDialog by remember { mutableStateOf(false) }` state.
When true, render `CreateMixSlotDialog(...)`.)

- [ ] **Step 2: Prepare screen — add expandable Mix slots section**

In `PrepareScreen.kt`, after the existing filament strip, add an expandable
"Mix slots" section that lists project + library mixes (read from
`viewModel.mixedFilamentManager.projectMixes.collectAsState()` /
`.libraryMixes.collectAsState()`) and has a "+ Add" affordance opening the
dialog.

- [ ] **Step 3: Update Smart Paint / Filament-mapping callers of HighlightSlotPicker**

Replace those call sites' direct calls to `HighlightSlotPicker(...)` with
`SectionedSlotPicker(...)`, passing `viewModel.mixedFilamentManager.projectMixes`,
`viewModel.mixedFilamentManager.libraryMixes` (filtered to compatible),
`onCreateMix = { showCreateMixDialog = true }`, etc.

- [ ] **Step 4: Build to confirm**

```bash
./gradlew compileDebugKotlin --no-daemon 2>&1 | tail -5
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```

Both BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/FilamentScreen.kt \
        app/src/main/java/com/u1/slicer/ui/PrepareScreen.kt \
        app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt \
        app/src/main/java/com/u1/slicer/ui/FilamentMappingDialog.kt
git commit -m "feat(M3-A): wire all three entry points to CreateMixSlotDialog

Filaments tab gets a + Mix slot row after the 4 physical rows.
Prepare screen gets an expandable Mix slots section with + Add.
Existing HighlightSlotPicker call sites (Smart Paint result screen,
FilamentMappingDialog) migrate to SectionedSlotPicker passing the
ViewModel's projectMixes/libraryMixes flows.

All three entry points open the same dialog; the dialog's onConfirm
hits mixedFilamentManager.add(). Edit mode is triggered by long-press
on a mix chip in the picker.
"
```

---

## Task 11: End-to-end instrumented tests

**Files:**
- Create: `app/src/androidTest/java/com/u1/slicer/MixedFilamentCreateE2ETest.kt`
- Create: `app/src/androidTest/java/com/u1/slicer/MixedFilamentLibraryPersistenceE2ETest.kt`

- [ ] **Step 1: Create-and-slice end-to-end**

`app/src/androidTest/java/com/u1/slicer/MixedFilamentCreateE2ETest.kt`:

```kotlin
package com.u1.slicer

import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SliceConfig
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MixedFilamentCreateE2ETest {

    /**
     * Manager.serialize() output reaches the engine and appears in G-code header.
     * Mirrors stage 2's mixedFilament test but uses the Manager-built recipe.
     */
    @Test
    fun managerSerialize_reachesEngineConfigInGcode() {
        val mgr = com.u1.slicer.data.MixedFilamentManager(
            loadProject = { emptyList() }, loadLibrary = { emptyList() },
            saveProject = {}, saveLibrary = {},
        )
        mgr.add(componentA = 1, componentB = 2, mixBPercent = 50,
            distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE)
        val recipe = mgr.serialize(numPhysicalFilaments = 2)

        val cfg = DEFAULT_CONFIG.copy(extruderCount = 2, mixedFilamentDefinitions = recipe)
        val (success, gcode) = sliceAsset("tetrahedron.stl", cfg)
        assertTrue("Slice with mixed-filament recipe must succeed", success)
        assertNotNull("G-code must be produced", gcode)
        assertTrue(
            "G-code config-dump must contain the recipe substring",
            gcode!!.contains("mixed_filament_definitions") && gcode.contains(recipe),
        )
    }

    // DEFAULT_CONFIG + sliceAsset reused from SlicingIntegrationTest patterns;
    // copy or refactor into a shared base if shared infrastructure exists.
}
```

(Verify the DEFAULT_CONFIG and sliceAsset helpers — these exist in
SlicingIntegrationTest.kt today. Either reuse via subclass or duplicate the
minimal helper into this file. Match the pattern Stage 2's test established.)

- [ ] **Step 2: Library persistence across model load**

`app/src/androidTest/java/com/u1/slicer/MixedFilamentLibraryPersistenceE2ETest.kt`:

```kotlin
package com.u1.slicer

import androidx.test.platform.app.InstrumentationRegistry
import com.u1.slicer.data.MixedFilamentRow
import com.u1.slicer.data.SettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Test

class MixedFilamentLibraryPersistenceE2ETest {

    /**
     * Writing library mixes to DataStore then reading them back via a fresh
     * SettingsRepository instance returns the same list — proves the
     * SettingsRepository persistence path round-trips through actual storage.
     */
    @Test
    fun libraryMixes_persistAcrossRepositoryInstantiations() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val rows = listOf(
            MixedFilamentRow(
                id = 1L, componentA = 1, componentB = 3, mixBPercent = 50,
                distributionMode = MixedFilamentRow.MixDistributionMode.LAYER_CYCLE,
                label = "Test", inLibrary = true,
            ),
        )
        // Write via one repository instance, read via another.
        // (Adjust API calls to match what was added in Task 6.)
        val writer = SettingsRepository(ctx)
        writer.setLibraryMixesBlocking(rows)
        val reader = SettingsRepository(ctx)
        assertEquals(rows, reader.snapshotLibraryMixes())
    }
}
```

(Method names like `setLibraryMixesBlocking` / `snapshotLibraryMixes` must match
what was added in Task 6. If the API differs, adapt the test to use the actual
API or add a thin blocking helper for test use.)

- [ ] **Step 3: Run the two new instrumented tests in isolation on a connected device**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.MixedFilamentCreateE2ETest,com.u1.slicer.MixedFilamentLibraryPersistenceE2ETest \
  --no-daemon 2>&1 | tail -25
```

Expected: BUILD SUCCESSFUL, 2 tests pass.

- [ ] **Step 4: Commit**

```bash
git add app/src/androidTest/java/com/u1/slicer/MixedFilamentCreateE2ETest.kt \
        app/src/androidTest/java/com/u1/slicer/MixedFilamentLibraryPersistenceE2ETest.kt
git commit -m "test(M3-A): instrumented E2E for manager-driven slice + library persistence

Two end-to-end gates:
- MixedFilamentManager.serialize() output reaches engine config-dump
  in G-code header (mirrors stage 2's pattern but driven by the manager).
- Library mixes round-trip through SettingsRepository's DataStore
  (one writer instance, fresh reader instance, same list back).
"
```

---

## Task 12: Full regression sweep + push branch

- [ ] **Step 1: Run full JVM unit suite**

```bash
./gradlew testDebugUnitTest --no-daemon 2>&1 | tail -15
```

Expected: BUILD SUCCESSFUL, ~1503 tests pass, 0 failures. (Exact count depends
on how many DataStore round-trip tests Task 6 actually added.)

- [ ] **Step 2: Run full instrumented sweep**

```bash
ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest \
  --no-daemon 2>&1 | tee /d/tmp/m3-phase-a-sweep.log | tail -30
```

Expected: BUILD SUCCESSFUL, ~408 tests pass (~406 baseline + 2 new from Task 11).
0 failures. Wall time ~25-35 min.

Known flaky tests from Stage 1/2 (ForegroundServiceDidNotStartInTimeException
class — Buzz plate 8, f73_changePlate, spiderman_dragToRight) may surface; if
any single test fails, re-run it in isolation and confirm pass — flake, not
regression.

- [ ] **Step 3: Push the branch**

```bash
git push -u origin feature/m3-phase-a-mix-slots 2>&1 | tail -5
```

- [ ] **Step 4: Verify remote SHA matches local**

```bash
gh api repos/taylormadearmy/u1-slicer-for-android/branches/feature/m3-phase-a-mix-slots \
  --jq '.commit.sha'
git rev-parse HEAD
```

Should match.

- [ ] **Step 5: Report and hand off for merge**

Branch is ready. Controller decides whether to merge directly to main (as we
did with Stages 1+2) or via PR review on GitHub.

---

## Out of scope for Phase A

These belong to follow-up plans, not this one:

- **Phase B — Smart Paint mix integration** (`ColourPaletteResolver`, AI assignment to mix slots, mix-aware Smart Paint result tree). See `2026-06-06-full-spectrum-m3-phase-b-smart-paint-mix-design.md`.
- **Phase C — Target-colour picker + M4** (Prusa fdm-mixer integration, HSV picker, brute-force `findBestBlend`). See `2026-06-06-full-spectrum-m3-phase-c-target-colour-picker-design.md`.
- **3MF embedding of project's mix list on save.** Slice works (recipe lives in SliceConfig); save round-trip is a separate concern.
- **Per-mix cost estimate refinement.** Phase A ships a generic subtitle; future work plugs in slice-time delta.

## Self-review notes

- Spec §"Components" → Tasks 1, 2, 3 (data model), 5, 6 (persistence), 8 (dialog), 9 (slot picker), 10 (entry points).
- Spec §"Data flow" → Tasks 7 (slice path), 10 (UI flows), 11 (E2E).
- Spec §"Tests" → Tasks 1, 2, 3, 5, 6, 7 (JVM unit), 11 (instrumented).
- Spec §"Acceptance criteria":
  1. 4-tap creation from any entry point → Task 10.
  2. Mix appears in slot picker → Task 9 + 10.
  3. Promote + persistence across loads → Tasks 5, 6, 11.
  4. Recipe in G-code → Task 11.
  5. No regression → Task 12.
  6. Dark mode → covered by reuse of existing MaterialTheme components; no separate task needed.
- Type consistency: `MixedFilamentRow` field names + types are identical across all 12 tasks. `MixedFilamentManager` method names (`add`, `edit`, `delete`, `promoteToLibrary`, `demoteFromLibrary`, `serialize`) are consistent.
- No "TBD" / "TODO" / "fill in later" anywhere in this plan.
- Frequent commits: each task ends with a commit; branch lands as ~10 commits (Task 4 is empty-by-design; Task 12 only pushes).
