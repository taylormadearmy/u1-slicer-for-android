# Bambu Printer Support — Sub-projects A + B: Transport Refactor + LAN Read-Only

**Date:** 2026-05-24
**Scope:** First shippable slice of multi-sub-project Bambu printer integration. See [`2026-05-24-bambu-integration-roadmap.md`](2026-05-24-bambu-integration-roadmap.md) for the full A→F roadmap.
**Status:** Design — not yet implemented. Internal-only build; not released externally until sub-project C (passthrough send) also ships.

## Goal

1. **A — Transport refactor:** Extract a `PrinterTransport` interface from the Moonraker-coupled `PrinterRepository` so additional printer kinds can plug in without touching the repository. No user-visible behaviour change for existing U1 / Moonraker users.
2. **B — Bambu LAN read-only:** Add Bambu LAN printers as a new `PrinterKind`. App connects directly to Bambu printers over MQTT-TLS, displays live status, AMS slot inventory, and an in-app MJPEG camera tile. **No send / start / cancel / pause / resume** — those land in sub-project C.

Inspired by [bambuddy](https://github.com/maziggy/bambuddy) (self-hosted Bambu management daemon), whose Python implementation is the reference for the MQTT, FTPS, and camera-socket wire formats.

## Non-goals (this sub-project)

- Sending files, starting prints, cancelling/pausing/resuming — sub-project C.
- SSDP / mDNS discovery in the add-printer dialog — sub-project D.
- Slicing for Bambu printers — sub-project E.
- Off-LAN relay through a bambuddy instance — sub-project F.
- Bambu Cloud MQTT (vendor cloud) — out of roadmap.

## Constraints

- **F78 multi-printer baseline must ship first.** A+B builds on `PrinterRepository` + `PrintersRepository` from F78. If F78 lands as v2.4.0, A+B targets v2.5.0 internal.
- **Single-active model preserved.** Only the active printer holds a live connection. Switching printers tears down the old transport and starts the new one.
- **No native rebuild required.** Pure Kotlin/JVM work.
- **No destructive operations.** Read-only means no MQTT publishes that change printer state.

## Architecture

### Transport interface

```kotlin
interface PrinterTransport {
    val status: Flow<PrinterStatus>
    val amsSlots: Flow<List<AmsSlot>>     // empty list for printers without AMS
    val cameraState: Flow<CameraState>    // Disabled / Connecting / Streaming(frames: Flow<Bitmap>) / Error

    suspend fun start(scope: CoroutineScope)
    suspend fun stop()
    suspend fun testConnection(): String? // null on success, error message otherwise
}

class MoonrakerTransport(private val client: MoonrakerClient) : PrinterTransport { ... }
class BambuLanTransport(private val config: BambuConfig) : PrinterTransport { ... }
```

Send methods (`uploadFile`, `startPrint`, `cancelPrint`, `pausePrint`, `resumePrint`) are deliberately absent — added in sub-project C. The interface grows; this is not a corner-paint risk because both `MoonrakerTransport` and `BambuLanTransport` will gain those methods in lock-step.

### PrinterRepository changes

Current state (post-F78):

```kotlin
class PrinterRepository(
    appContext: Context,
    client: MoonrakerClient,                 // tightly coupled
    printersRepo: PrintersRepository,
)
```

Refactored:

```kotlin
class PrinterRepository(
    appContext: Context,
    transportFactory: PrinterTransportFactory,
    printersRepo: PrintersRepository,
) {
    private var active: PrinterTransport? = null
    val status: StateFlow<PrinterStatus>     // forwarded from active.status
    val amsSlots: StateFlow<List<AmsSlot>>   // forwarded from active.amsSlots
    val cameraState: StateFlow<CameraState>  // forwarded from active.cameraState
    val activeNickname: StateFlow<String>
    val printerCount: StateFlow<Int>
}

interface PrinterTransportFactory {
    fun create(printer: Printer): PrinterTransport
}

class DefaultPrinterTransportFactory(
    private val moonrakerClient: MoonrakerClient,  // singleton, baseUrl swapped per Moonraker printer
) : PrinterTransportFactory {
    override fun create(printer: Printer): PrinterTransport = when (printer.kind) {
        PrinterKind.MOONRAKER -> {
            moonrakerClient.baseUrl = MoonrakerClient.normalizeUrl(printer.moonrakerUrl)
            MoonrakerTransport(moonrakerClient)
        }
        PrinterKind.BAMBU_LAN -> BambuLanTransport(printer.bambu!!)
    }
}
```

Active-printer switch logic:
1. `printersRepo.config.collect` fires with new active.
2. `active?.stop()` if present.
3. `active = transportFactory.create(newActive)`.
4. `active.start(repositoryScope)`.
5. Forward status / amsSlots / cameraState into the repository-owned `StateFlow`s.

### Data model

`Printer` (extends existing F78 data class):

```kotlin
enum class PrinterKind { MOONRAKER, BAMBU_LAN }
enum class BambuModel { X1C, X1E, P1S, P1P, A1, A1_MINI, H2D }

data class BambuConfig(
    val ip: String,
    val accessCode: String,    // exactly 8 digits, from printer screen
    val serial: String,        // non-empty alphanumeric
    val model: BambuModel,
)

data class Printer(
    val id: String,
    val nickname: String,
    val kind: PrinterKind = PrinterKind.MOONRAKER,
    val moonrakerUrl: String = "",         // populated iff kind == MOONRAKER
    val bambu: BambuConfig? = null,        // populated iff kind == BAMBU_LAN
    val extruderPresets: List<ExtruderPreset> = emptyList(),  // empty for BAMBU_LAN
) {
    init {
        when (kind) {
            PrinterKind.MOONRAKER -> {
                require(moonrakerUrl.isNotEmpty()) { "Moonraker printer needs moonrakerUrl" }
                require(bambu == null) { "Moonraker printer must not have bambu config" }
            }
            PrinterKind.BAMBU_LAN -> {
                require(bambu != null) { "Bambu printer needs bambu config" }
                require(moonrakerUrl.isEmpty()) { "Bambu printer must not have moonrakerUrl" }
            }
        }
    }
}
```

AMS slot data:

```kotlin
data class AmsSlot(
    val unit: Int,             // 0 for X1/P1/A1; 0 or 1 for H2D
    val slot: Int,             // 0..3
    val materialType: String?, // "PLA", "PETG", "PA-CF", etc. — null if empty
    val hexColor: String?,     // "#RRGGBB", null if empty
    val remainingPct: Int?,    // 0..100 if printer reports it, else null
)
```

Camera state:

```kotlin
sealed class CameraState {
    data object Disabled : CameraState()
    data object Connecting : CameraState()
    data class Streaming(val frames: Flow<Bitmap>) : CameraState()
    data class Error(val message: String) : CameraState()
}
```

### Settings backup version bump

`SettingsBackup` VERSION 2 → 3:

- v2 import (no `kind` field) → defaults to `kind = MOONRAKER`, `bambu = null`.
- v3 export writes the union shape: `kind`, `moonrakerUrl` (Moonraker only), `bambu` object (Bambu only).

## BambuLanTransport internals

Three concurrent connections per active Bambu printer, all torn down on `stop()`.

### MQTT-TLS

- **Library:** `org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5`. Paho's Android Service is deprecated; we use plain `MqttClient` on a coroutine scope.
- **Endpoint:** `tcps://{ip}:8883`, username `bblp`, password = access code.
- **TLS:** Bambu printers ship a self-signed certificate. Trust-all `SSLContext` is acceptable here (bambuddy and BambuStudio both do this — there is no upstream CA to pin against).
- **Subscribe:** `device/{serial}/report` (push reports, ~1 Hz heartbeat + state-change bursts).
- **Bootstrap:** publish `{ "pushing": { "command": "pushall" } }` to `device/{serial}/request` once on connect to force a full state dump.
- **Reconnect:** exponential backoff (1s, 2s, 5s, 30s, capped) on disconnect. Status transitions to `"disconnected"` after grace period.

Push reports flow through a parser that emits:
- `print.gcode_state` → `PrinterStatus.state` (`IDLE` / `PREPARE` / `RUNNING` / `PAUSE` / `FINISH` / `FAILED`)
- `print.mc_percent` → `PrinterStatus.progress`
- `print.nozzle_temper`, `print.bed_temper` + targets → temps
- `print.ams.ams[]` → list of `AmsSlot`

Per-model branches in the parser:
- **X1C / X1E / P1S / P1P:** single AMS unit at `ams.ams[0]`, 4 trays.
- **A1 / A1_MINI:** AMS Lite at `ams.ams[0]`, 4 trays, slightly different temper fields.
- **H2D:** dual-AMS topology at `ams.ams[0]` and `ams.ams[1]`; dual-nozzle temper arrays. Schema is newer (2025) and treated as best-effort; unknown fields tolerated.

### Camera (HTTP MJPEG via authenticated TCP)

- **Endpoint:** raw TCP socket to printer IP on a model-specific port (bambuddy uses 6000 for most models; TLS wrapping varies by firmware). Exact port and TLS settings are taken from bambuddy at implementation time — the handshake is undocumented officially and bambuddy's Python implementation is the ground truth.
- **Handshake:** send a fixed-length authentication blob containing the serial number and access code, then read length-prefixed JPEG frames. Exact blob layout taken from bambuddy.
- **Frame loop:** decode JPEG → `Bitmap` → emit on `CameraState.Streaming.frames`.
- **Threading:** dedicated `Dispatchers.IO` reader; frames marshalled onto Compose via `LaunchedEffect(cameraState)`.
- **H2D:** schema unverified at design time. Ship with camera disabled for H2D (`CameraState.Error("Camera not yet supported on H2D")`) until verified on hardware.

### Disconnect semantics

- AMS state is **not persisted** across app restart. On disconnect, AMS card shows "AMS state unknown".
- Status falls back to `"disconnected"` after the grace period.
- Camera transitions to `CameraState.Disabled` immediately on stop().

## UI changes

### Add-printer dialog (`PrinterEditDialog.kt`)

- New `PrinterKind` segmented control at the top: `Moonraker` (default) | `Bambu (LAN)`.
- Moonraker branch: existing IP / nickname / extruder-presets fields.
- Bambu branch:
  - IP (validated as IPv4 or hostname)
  - Access code (exactly 8 digits, numeric)
  - Serial (non-empty alphanumeric)
  - Model dropdown (X1C / X1E / P1S / P1P / A1 / A1 Mini / H2D)
  - Nickname
- SSDP discovery is sub-project D; for A+B the Bambu branch is manual entry only.

### Printer tab (`PrinterScreen.kt`)

When active printer's `kind == BAMBU_LAN`, layout swaps:

- **Status badge:** existing `PrinterStatusBadge` composable, fed from `BambuLanTransport.status` (no logic changes — the badge consumes `PrinterStatus`).
- **Camera tile:** full-width card above AMS; renders frames from `CameraState.Streaming`. Tap → fullscreen view. Placeholder when `CameraState != Streaming`.
- **AMS inventory card:** new composable `AmsInventoryCard`. 4-tile grid (or 8 tiles in two rows for H2D) showing per-slot material type label + colour swatch + remaining-percentage chip. Empty slot → grey "Empty" tile.
- **Hidden:** send-file row, Map & Print button, extruder-preset row. (Re-enabled in sub-project C / E.)

When active printer's `kind == MOONRAKER`, layout is unchanged.

### Active-printer chip + switcher sheet

Unchanged behaviourally. Bambu entries get a small "Bambu" label badge in the switcher sheet list.

### Settings → Printers card

Unchanged behaviourally. Bambu entries appear alongside Moonraker entries; tap edits via the same dialog (which routes by `kind`).

## Testing strategy

### Unit tests (JVM)

Add to existing files:
- `PrinterTest.kt` — Bambu-kind JSON round-trip, invariant enforcement (kind/config mismatch throws).
- `PrintersRepositoryTest.kt` — v2→v3 migration leaves Moonraker entries untouched; new Bambu entries persist.
- `SettingsBackupTest.kt` — v2 import (no kind field) defaults to MOONRAKER; v3 round-trip.

New files:
- `BambuPushReportParserTest.kt` — canonical report JSON fixtures (one per model family: X1C, P1S, A1, H2D). Assert correct `PrinterStatus`, `AmsSlot[]`, temps. Fixtures sourced from ha-bambulab and bambulabs-api reference samples.
- `BambuCameraHandshakeTest.kt` — handshake byte construction, length-prefix frame parser, malformed-frame guard, partial-read resumption.

### Instrumented tests (Android)

Add to existing files:
- `MultiPrinterIntegrationTest.kt` (from F78) — extended with Bambu-kind add/edit/delete.

New files:
- `BambuLanTransportMqttTest.kt` — embed HiveMQ test broker in-process; publish recorded push reports; assert `status` / `amsSlots` flows emit correctly.
- `BambuLanTransportReconnectTest.kt` — kill the broker mid-stream; assert exponential backoff and recovery.
- `PrinterRepositoryTransportSwapTest.kt` — switch active printer Moonraker → Bambu → Moonraker; assert clean transport teardown, no leaked coroutines, status flow continuity.
- `BambuCameraFrameDecodeTest.kt` — feed recorded frame bytes into a mock socket; assert `Bitmap` emissions are valid.

### Real-hardware verification (gates merge)

Before merging A+B:
- Connect to at least one real Bambu printer (any model in scope) on LAN.
- Verify status badge transitions across at least one print cycle (Idle → Prepare → Running → Finish).
- Verify AMS inventory matches what's loaded.
- Verify camera tile renders frames for ~30 seconds without crash.
- Verify clean disconnect when active printer is switched away.

If hardware unavailable at merge time, A+B can be staged in main but the version bump and external release are blocked until verified.

## Build & dependencies

**New Gradle dependencies (main):**
- `org.eclipse.paho:org.eclipse.paho.mqttv5.client:1.2.5` (~250 KB compressed)

**New Gradle dependencies (androidTest only):**
- `com.hivemq:hivemq-mqtt-client:1.3.3` for in-process broker in instrumented tests (~1 MB, test scope only — does not affect APK)

**APK size impact:** ~300 KB compressed. Acceptable.

**Version bump:** next available versionCode + minor bump after F78 lands. Internal-only build per agreed scope — no external release until sub-project C also ships.

**No native rebuild.**

## Risk register

1. **Paho v5 lifecycle on Android.** Paho's Android Service is deprecated; we use `MqttClient` on a coroutine scope. Worth a one-day spike during the first task of implementation to confirm the plain client behaves well on background-network transitions (WiFi sleep, doze mode).
2. **Bambu camera socket handshake is undocumented officially.** We rely on bambuddy's Python implementation as ground truth. Mitigation: dedicated `BambuCameraHandshakeTest.kt` unit suite with byte-for-byte fixtures captured from a real handshake; instrumented `BambuCameraFrameDecodeTest.kt` exercising the framing parser.
3. **H2D protocol divergence.** H2D launched 2025; push report schema may carry undocumented fields. Mitigation: parser is lenient (unknown fields ignored), camera disabled for H2D until verified on hardware, "best-effort" label on the model picker.
4. **Self-signed TLS.** Trust-all `SSLContext` is required (no upstream CA, no per-device cert provisioning). Documented in code with a `// SECURITY:` comment explaining why.

## Acceptance criteria

- All unit tests pass.
- All instrumented tests pass.
- Real-hardware verification checklist completed against at least one Bambu printer.
- Switching active printer between a Moonraker entry and a Bambu entry works in both directions with no crash, no leaked coroutines, and correct UI swap.
- F78 multi-printer functionality remains unchanged for Moonraker users (regression-tested via existing `MultiPrinterIntegrationTest.kt`).

## Out of scope (explicitly)

- Sending files, starting prints, cancelling/pausing/resuming — sub-project C.
- SSDP discovery in add-printer dialog — sub-project D.
- Slicing for Bambu printers — sub-project E.
- Bambuddy off-LAN relay transport — sub-project F.
- Bambu Cloud MQTT auth — out of roadmap entirely.
- Concurrent MQTT connections to multiple Bambu printers — single-active model preserved.
