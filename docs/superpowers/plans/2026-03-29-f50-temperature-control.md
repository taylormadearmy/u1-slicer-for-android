# F50: Printer Temperature Control Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow users to adjust bed and extruder (E1–E4) temperatures mid-print by tapping the target temperature in the Printer screen, which opens an inline text field that sends a Moonraker G-code command on commit.

**Architecture:** Add `sendGcode()` to `MoonrakerClient` → `setHeaterTemperature()` convenience wrapper → expose through `PrinterRepository` → `PrinterViewModel.setHeaterTemperature()`. In `PrinterScreen`, tap on target temp chip switches it to an inline `BasicTextField`; on Done/focus-loss, calls the ViewModel method.

**Tech Stack:** Kotlin, Jetpack Compose, Material3, OkHttp (MoonrakerClient), Moonraker REST API

---

### Task 1: Add `sendGcode` to MoonrakerClient

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt`
- Test: `app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt`

- [ ] **Step 1: Write a failing test for `sendGcode`**

Open `app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt`. Add:

```kotlin
@Test
fun `setHeaterTemperature builds correct gcode string`() {
    // Verify the gcode string format — we test the command string construction
    // without a live server by checking the format directly.
    val cases = listOf(
        Triple("heater_bed", 60, "SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=60"),
        Triple("extruder", 200, "SET_HEATER_TEMPERATURE HEATER=extruder TARGET=200"),
        Triple("extruder1", 215, "SET_HEATER_TEMPERATURE HEATER=extruder1 TARGET=215"),
    )
    cases.forEach { (heater, target, expected) ->
        assertEquals(expected, MoonrakerClient.buildSetHeaterGcode(heater, target))
    }
}

@Test
fun `setHeaterTemperature clamps to safe range`() {
    assertEquals("SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=0",
        MoonrakerClient.buildSetHeaterGcode("heater_bed", -10))
    assertEquals("SET_HEATER_TEMPERATURE HEATER=heater_bed TARGET=120",
        MoonrakerClient.buildSetHeaterGcode("heater_bed", 999))
    assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder TARGET=0",
        MoonrakerClient.buildSetHeaterGcode("extruder", -5))
    assertEquals("SET_HEATER_TEMPERATURE HEATER=extruder TARGET=300",
        MoonrakerClient.buildSetHeaterGcode("extruder", 999))
}
```

- [ ] **Step 2: Run tests to confirm they fail**

```bash
./gradlew testDebugUnitTest --tests "*.MoonrakerClientTest" --no-daemon 2>&1 | tail -15
```
Expected: FAILED — `buildSetHeaterGcode` not defined.

- [ ] **Step 3: Add `buildSetHeaterGcode`, `sendGcode`, and `setHeaterTemperature` to MoonrakerClient**

In `MoonrakerClient.kt`, add after `cancelPrint()`:

```kotlin
suspend fun sendGcode(gcode: String): Boolean = withContext(Dispatchers.IO) {
    try {
        val body = org.json.JSONObject().put("script", gcode).toString()
            .toRequestBody("application/json".toMediaType())
        val response = httpClient.newCall(
            Request.Builder().url("$baseUrl/printer/gcode/script").post(body).build()
        ).execute()
        response.isSuccessful
    } catch (_: Exception) { false }
}

suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
    sendGcode(buildSetHeaterGcode(heater, targetC))

companion object {
    internal fun buildSetHeaterGcode(heater: String, targetC: Int): String {
        val isBed = heater == "heater_bed"
        val clamped = targetC.coerceIn(0, if (isBed) 120 else 300)
        return "SET_HEATER_TEMPERATURE HEATER=$heater TARGET=$clamped"
    }
}
```

Note: check if `MoonrakerClient` already has a `companion object` — if so, add `buildSetHeaterGcode` inside the existing one rather than creating a new one.

- [ ] **Step 4: Run tests to confirm they pass**

```bash
./gradlew testDebugUnitTest --tests "*.MoonrakerClientTest" --no-daemon 2>&1 | tail -15
```
Expected: all MoonrakerClientTest tests PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt \
        app/src/test/java/com/u1/slicer/network/MoonrakerClientTest.kt
git commit -m "feat(F50): add sendGcode + setHeaterTemperature to MoonrakerClient"
```

---

### Task 2: Expose `setHeaterTemperature` through PrinterRepository and PrinterViewModel

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt`
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt`

- [ ] **Step 1: Add to PrinterRepository after `setLed()`**

```kotlin
suspend fun setHeaterTemperature(heater: String, targetC: Int): Boolean =
    client.setHeaterTemperature(heater, targetC)
```

- [ ] **Step 2: Add to PrinterViewModel after `cancelPrint()`**

```kotlin
fun setHeaterTemperature(heater: String, targetC: Int) {
    viewModelScope.launch {
        val ok = printerRepo.setHeaterTemperature(heater, targetC)
        if (!ok) _heaterError.value = "Could not update temperature"
    }
}
```

Add the backing state for the error Snackbar at the top of `PrinterViewModel` alongside other `MutableStateFlow`s:

```kotlin
private val _heaterError = MutableStateFlow<String?>(null)
val heaterError: StateFlow<String?> = _heaterError.asStateFlow()

fun clearHeaterError() { _heaterError.value = null }
```

- [ ] **Step 3: Compile check**

```bash
./gradlew assembleDebug --no-daemon 2>&1 | tail -5
```
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer/PrinterRepository.kt \
        app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt
git commit -m "feat(F50): expose setHeaterTemperature through PrinterRepository and PrinterViewModel"
```

---

### Task 3: Add temperature editing UI to PrinterScreen

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt`

The Printer screen already shows temperature tiles (`TempTile`) when `status.isConnected`. The editing UI is added to the existing print-active section (where `status.isPrinting || status.isPaused` is checked, around line 385).

- [ ] **Step 1: Add a `editingHeater` local state variable at the top of `PrinterScreen`**

Inside the `PrinterScreen` composable, near the other `remember` variables:

```kotlin
var editingHeater by remember { mutableStateOf<String?>(null) }
var editingValue by remember { mutableStateOf("") }
val heaterError by viewModel.heaterError.collectAsState()
```

- [ ] **Step 2: Add a `SnackbarHost` for heater errors**

The Printer screen uses `Scaffold`. Find the `Scaffold` call and add (or extend the existing `snackbarHostState`):

```kotlin
val snackbarHostState = remember { SnackbarHostState() }
LaunchedEffect(heaterError) {
    heaterError?.let {
        snackbarHostState.showSnackbar(it)
        viewModel.clearHeaterError()
    }
}
```

Add `snackbarHost = { SnackbarHost(snackbarHostState) }` to the `Scaffold` if not already present.

- [ ] **Step 3: Add the editable temperature section**

Find the block `if (status.isPrinting || status.isPaused)` (around line 385). Inside it, add a "Temperatures" card after the existing controls:

```kotlin
if (status.isPrinting || status.isPaused) {
    // ... existing pause/cancel buttons ...

    Spacer(Modifier.height(12.dp))
    Text("Temperatures", style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(horizontal = 16.dp))
    Spacer(Modifier.height(4.dp))

    // Build heater list: bed first, then extruders
    val heaters = buildList {
        add(Triple("heater_bed", "Bed", status.bedTarget))
        status.extruders.forEachIndexed { i, ext ->
            val key = if (i == 0) "extruder" else "extruder$i"
            add(Triple(key, "E${i + 1}", ext.target))
        }
        // Fallback if extruders list is empty (older firmware)
        if (status.extruders.isEmpty() && (status.nozzleTemp > 0 || status.nozzleTarget > 0)) {
            add(Triple("extruder", "E1", status.nozzleTarget))
        }
    }

    heaters.forEach { (heaterKey, label, currentTarget) ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, modifier = Modifier.width(40.dp),
                style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.weight(1f))
            if (editingHeater == heaterKey) {
                BasicTextField(
                    value = editingValue,
                    onValueChange = { editingValue = it.filter { c -> c.isDigit() } },
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = {
                        val v = editingValue.toIntOrNull()
                        if (v != null) viewModel.setHeaterTemperature(heaterKey, v)
                        editingHeater = null
                    }),
                    singleLine = true,
                    modifier = Modifier
                        .width(64.dp)
                        .border(1.dp, MaterialTheme.colorScheme.primary,
                            MaterialTheme.shapes.small)
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                )
            } else {
                Text(
                    text = "→ ${currentTarget.toInt()}°C",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable {
                        editingHeater = heaterKey
                        editingValue = currentTarget.toInt().toString()
                    }
                )
            }
        }
    }
}
```

Required imports (add to top of file if not present):
```kotlin
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.unit.dp
```

- [ ] **Step 4: Build and smoke-test**

```bash
./gradlew installDebug --no-daemon 2>&1 | tail -5
```

Connect to a printer with an active print. Verify the temperature rows appear, tapping a target opens the text field, entering a value and pressing Done sends the command (check Moonraker logs or observe temp change on printer).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt
git commit -m "feat(F50): add inline temperature editing to Printer screen during active prints"
```
