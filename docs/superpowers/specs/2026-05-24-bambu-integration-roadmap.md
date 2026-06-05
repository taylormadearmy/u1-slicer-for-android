# Bambu Printer Integration — Roadmap (Sub-projects A → F)

**Date:** 2026-05-24
**Status:** Outline. Only sub-projects A + B are fully designed (see [`2026-05-24-bambu-ab-design.md`](2026-05-24-bambu-ab-design.md)). C / D / E / F are sketches at outline depth — each gets its own spec when it reaches the front of the queue.

## Why this is split

What "Bambu support" means in practice is six loosely-independent subsystems: a transport refactor, an MQTT/FTPS/camera client, a discovery flow, a re-slicing pipeline, AMS mapping UI, and an off-LAN relay. Specifying all of it as a single design produces a document too large to plan against, and burns cycles on details (e.g. slice-for-Bambu plate UI) before the fundamentals (e.g. can we talk MQTT-TLS to a Bambu printer from Android?) are validated. Decomposing lets each piece ship behind the previous one with its own design → plan → implement → verify cycle.

`bambuddy` (Python + TS Docker daemon — https://github.com/maziggy/bambuddy) is the reference implementation for the Bambu wire protocols. We do not embed it; we port the parts we need.

## Sub-project list

| # | Sub-project | What ships | Depends on | Roadmap status |
|---|---|---|---|---|
| **A** | Printer transport abstraction | `PrinterTransport` interface, `MoonrakerTransport` impl. No user-visible change. | F78 baseline | **Designed** (this batch) |
| **B** | Bambu LAN read-only | Bambu printer entries, MQTT-TLS push reports, AMS inventory, in-app MJPEG camera. **No send.** | A | **Designed** (this batch) |
| **C** | Bambu LAN passthrough send | FTPS upload + MQTT print command. "Send original to Bambu" button (source must be Bambu 3MF). AMS mapping UI. | B | Outline only |
| **D** | SSDP discovery in add-printer dialog | bambuddy-style SSDP browse + access-code field. | B (or C) | Outline only |
| **E** | Slice-for-Bambu | Bundle Bambu machine/process/filament profiles into the native engine, switch Prepare's plate/build-volume on active-printer change, second "Slice for Bambu & Send" button. | C | Outline only |
| **F** | Bambuddy off-LAN relay | Optional bambuddy URL per Bambu entry; HTTP/WS adapter as alternate transport when LAN unreachable. | C | Outline only |

External release of any of this is blocked until at least C ships — A and B alone don't deliver enough user value to justify the new surface area.

---

## A — Printer transport abstraction

Fully specified in [`2026-05-24-bambu-ab-design.md`](2026-05-24-bambu-ab-design.md) §Architecture / Transport interface. Summary:

- Extract `PrinterTransport` interface from the Moonraker-coupled `PrinterRepository`.
- `MoonrakerTransport` wraps existing `MoonrakerClient`; behavioural parity for U1 users.
- `PrinterTransportFactory` produces the right transport based on `Printer.kind`.
- `PrinterRepository` forwards status / amsSlots / cameraState flows from the active transport.
- No user-visible change. Internal refactor only.

---

## B — Bambu LAN read-only

Fully specified in [`2026-05-24-bambu-ab-design.md`](2026-05-24-bambu-ab-design.md). Summary:

- `Printer` data class extended with `kind: PrinterKind`, `bambu: BambuConfig?`.
- `BambuLanTransport` opens MQTT-TLS (port 8883), subscribes to `device/{serial}/report`, parses push reports into `PrinterStatus` + `AmsSlot[]`.
- In-app MJPEG camera tile via authenticated TCP socket (port 6322 / 6000 depending on model).
- Add-printer dialog gains a kind picker (Moonraker / Bambu LAN). Manual entry only — SSDP is sub-project D.
- Printer tab adapts when active is Bambu: status badge + camera tile + AMS card; send / Map & Print hidden.
- Per-model branches: X1C, X1E, P1S, P1P, A1, A1 Mini, H2D. H2D camera disabled until hardware-verified.

---

## C — Bambu LAN passthrough send

### Goal

"Send original to Bambu" button works end-to-end for source files that are unmodified Bambu 3MFs. After upload, the app sends an MQTT `project_file` command with the user's AMS mapping. The destructive "start print" step is gated by an explicit confirmation dialog (per CLAUDE.md project rule).

### New transport methods

`PrinterTransport` grows:

```kotlin
suspend fun uploadFile(file: File, remoteName: String): UploadResult
suspend fun startPrint(remoteName: String, amsMapping: IntArray, useAms: Boolean): CommandResult
suspend fun cancelPrint(): CommandResult
suspend fun pausePrint(): CommandResult
suspend fun resumePrint(): CommandResult
```

`MoonrakerTransport` gains the same methods (refactored from existing send code).

### Bambu-side components

- **`BambuFtpsClient`** — implicit FTPS on port 990, anonymous + access-code auth (apache `commons-net`). Uploads to root or `Metadata/plate_X.3mf` depending on file type. File size cap ~200 MB to work around P1/A1 FTPS quirks.
- **`AmsMappingDialog`** — extends the existing Map & Print dialog. Left column: filaments declared in the source 3MF (material type + colour). Right column: AMS slot tiles with live contents from `BambuLanTransport.amsSlots`. Auto-match by hex colour + material type; user can override. Output: `IntArray` of slot indices (length = file filament count).
- **MQTT print command:** publish `{ print: { command: "project_file", url: "ftp://.../{remoteName}", subtask_name, ams_mapping, use_ams } }` to `device/{serial}/request`.

### Safeguards

- Confirmation dialog before sending (mandatory per CLAUDE.md rule — never start a print without explicit user permission).
- Block if AMS reports an empty / missing slot referenced by the mapping.
- Show AMS state preview in the confirmation dialog so the user sees what they're committing to.
- "Send original" button is enabled **only** when source file is a Bambu 3MF (gated on file extension + 3MF metadata sniff).

### Tests

- In-process FTPS server (commons-net provides `FTPServer`) for upload-path tests.
- Mock MQTT broker (continue HiveMQ test broker pattern from B) for command-shape assertions.
- Real-hardware gate: complete one upload + print cycle on a physical Bambu printer before merge.

---

## D — SSDP discovery in add-printer dialog

### Goal

Replace manual IP / serial / model entry for Bambu printers with a live-discovery picker.

### Implementation sketch

- **`BambuSsdpDiscoverer`** — Android `NsdManager` UDP M-SEARCH on `239.255.255.250:1900`, target `urn:bambulab-com:device:3dprinter:1`. Listens for `NOTIFY ssdp:alive` broadcasts. Parses `DevModel.bambu.com`, `DevName.bambu.com`, `DevSerial.bambu.com` headers from the response payload.
- **Add-printer dialog** — Bambu kind opens a discovery sheet (mDNS-style live list with "scanning…" affordance and a manual-entry fallback link). Tap a discovered printer → fills IP / serial / model; user types access code only.
- **Permissions** — works on WiFi only (3G / 4G blocks multicast). No new permissions required on modern Android.
- **Tests** — in-process UDP mock; opt-in instrumented test if real LAN device is connected.

Smallest sub-project; could ship alongside B if scope allows.

---

## E — Slice-for-Bambu (biggest piece)

### Goal

App becomes a true multi-vendor slicer: "Slice for Bambu & Send" button produces a Bambu-compatible `.gcode.3mf` via the bundled Orca engine and uploads via sub-project C's transport.

### Internal sub-parts

1. **Bambu profile bundle**
   - Copy Bambu Studio's `machine_definition` / `process` / `filament` JSON for X1C / P1S / P1P / A1 / A1 Mini / H2D into `app/src/main/assets/bambu_profiles/`.
   - New `BambuProfileBundle` Kotlin class loads them on demand.

2. **Native engine plumbing**
   - Orca already accepts Bambu profile keys via the existing `applyConfigToPrusa` + `profile_keys[]` mechanism documented in CLAUDE.md.
   - May need Bambu-specific `machine_start_gcode` recognition and AMS-related per-extruder defaults.
   - **Native rebuild expected** (pre-authorised per CLAUDE.md). Follow the NDK 26 / Release / size + compiler verification checklist exactly.

3. **Prepare UI adaptation**
   - `SliceConfig` becomes parameterised by active printer's machine flavour (plate dimensions, build volume, wipe-tower availability swap).
   - Existing `selectedExtruder` / extruder chip row remains, but when active is Bambu the chips reflect AMS inventory (live) rather than user-entered presets.

4. **Send button wiring**
   - "Slice for Bambu & Send" button in Map & Print, available whenever active printer is Bambu (regardless of source file).
   - Slice → produce Bambu `.gcode.3mf` → upload via sub-project C's FTPS → start via sub-project C's MQTT command.

5. **Tests**
   - Slicing integration test per model family: X1C as proxy for P1 series, A1 as proxy for A1 Mini, H2D standalone.
   - G-code validation against Bambu Studio reference outputs (compare tool counts, layer counts, AMS commands).
   - Native correctness tests for any new JNI accessors.

By far the biggest sub-project. Likely a multi-week effort with its own internal phasing.

---

## F — Bambuddy off-LAN relay

### Goal

If user has a `bambuddy` instance reachable over VPN / public internet, route through it when the printer's LAN is unreachable.

### Implementation sketch

- **`BambuRelayTransport`** — same `PrinterTransport` interface as `BambuLanTransport`, wire format is bambuddy's REST + WebSocket API.
  - Status: bambuddy WS push subscribed via OkHttp WebSocket client.
  - Upload: HTTP POST to bambuddy's file API; bambuddy handles the FTPS leg.
  - Commands: HTTP POST to bambuddy's print API; bambuddy issues the MQTT publish.
- **Per-printer config** — Bambu entries gain optional `bambuddyUrl` + `bambuddyApiKey` fields.
- **Transport selection** — `PrinterTransportFactory` tries LAN first with N-second timeout; falls back to relay if configured and LAN times out.
- **Tests** — mock bambuddy server replaying recorded responses + auth handshake.

Independent of E; could ship before or after.

---

## Interface stability check

A + B's `PrinterTransport` interface deliberately lacks send methods. C extends the interface with `uploadFile` / `startPrint` / `cancelPrint` / `pausePrint` / `resumePrint`. Both `MoonrakerTransport` and `BambuLanTransport` gain these methods in lock-step under C, so no behavioural regression is possible from this growth. F adds a third implementation (`BambuRelayTransport`) at the same surface — same shape, different wire. E does not touch the transport interface — it operates at the slicing engine layer and reuses C's transports for delivery.

No corner-paint risk identified.

## Build order summary

1. F78 lands (multi-printer baseline) — already in flight.
2. A + B land together as a single internal release (no external ship yet).
3. C lands. First external release of Bambu support becomes viable.
4. D ships independently (small, deferrable).
5. E ships independently (biggest piece; requires native rebuild).
6. F ships independently (off-LAN relay).

Sequencing assumes single-developer cadence. Could parallelise D against E if needed.
