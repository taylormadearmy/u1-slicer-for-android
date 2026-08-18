# Bambu local slicing and LAN pipeline review

Updated: 2026-08-11

This review separates **implemented**, **statically verified**, and **field
verified**. The original review was read-only. Release validation later included
user-initiated successful physical prints on A1 Mini and H2D; no automated test
or agent action started a physical print.

## Reference baseline

- BambuStudio `v02.08.01.55` (`ba049f9`, 2026-07-14) profiles and current BBL
  model registry: <https://github.com/bambulab/BambuStudio>
- Bambuddy current `main` (`8d3abf414be73eeaead228fcf04e11c65390484c`,
  checked 2026-08-02) for LAN MQTT/FTPS behavior, AMS routing, nozzle guards,
  partial-state merging, Developer Mode handling, and camera model quirks:
  <https://github.com/maziggy/bambuddy>
- Bambu Lab's LAN security/Developer Mode announcement:
  <https://blog.bambulab.com/answering-network-security-concerns/>
- The app's native slicing engine remains OrcaSlicer 2.2.4. The single-nozzle
  machine G-code is pinned to templates compatible with that engine. Newer
  templates cannot safely be pasted in because they use newer placeholders and
  include fragments that 2.2.4 does not expose.

## Bottom line

The app has an end-to-end **standard 0.4 mm nozzle, local-LAN pipeline** for:

- X1 Carbon and X1E
- P1P and P1S
- A1 and A1 Mini
- H2D

Within that deliberately narrow scope, the implementation is code-complete:
target-specific slicing, machine G-code, `gcode.3mf` packaging, checksum and
machine preflight, AMS/external-spool mapping, FTPS upload, MQTT start, status,
pause/resume/cancel, and camera routing are present.

It is **not field-complete across the Bambu fleet**. Pixel-side native slicing
and manual E2E coverage exercise every supported target, and user-initiated
physical acceptance prints succeeded on A1 Mini and H2D. There has been no
physical acceptance print on X1/X1E/P1/P1S/A1, and no hardware is available for
those printers. The H2D camera path also remains a field-risk until a non-black
live frame is reconfirmed after the latest RTSPS fix.

## Current model coverage

| Model | Local 0.4 slice | `gcode.3mf` | LAN upload/start | AMS mapping | Camera route | Confidence without hardware |
|---|---|---|---|---|---|---|
| X1 Carbon | implemented | implemented | implemented | AMS + external | RTSPS 322 | medium-low: static/native only |
| X1E | implemented | implemented | implemented | AMS + external | RTSPS 322 | medium-low: static/native only |
| P1P | implemented | implemented | implemented | AMS + external | TCP JPEG 6000 | medium-low: static/native only |
| P1S | implemented | implemented | implemented | AMS + external | TCP JPEG 6000 | medium-low: static/native only |
| P2S | implemented | implemented | implemented | AMS + external | not exposed | medium-low: static/native only |
| A1 | implemented | implemented | implemented | AMS Lite + external | TCP JPEG 6000 | medium: shares the proven A-series path |
| A1 Mini | implemented | implemented | implemented | AMS Lite + external | TCP JPEG 6000 | field-verified: successful physical print on 01.04.00.00 firmware |
| H2D | implemented | implemented | implemented | AMS, AMS-HT, L/R external, FTS-aware imported projects | RTSPS 322 | field-verified: successful dual-nozzle-aware physical print |

“Implemented” is not a promise that untested firmware has accepted a print. It
means the entire path exists and is covered by focused JVM/native artifact
tests.

## Current BambuStudio printers not implemented for local slicing

The current official BBL registry also contains these selectable families or
profiles, none of which is exposed by this app:

- H2D Pro
- H2S
- H2C
- X2D
- A2L
- the generic/base X1 profile

Adding an enum label would not be enough. These require their own current
machine identity, dimensions and exclusions, machine G-code, kinematics,
camera profile, FTPS/command quirks, and routing rules. In particular:

- H2S is single-nozzle and has a different 340 x 320 x 340 envelope.
- P2S has an isolated backport of its official 0.4 mm profile/macros for the
  bundled OrcaSlicer 2.2.4 engine. Its RTSPS camera still needs Bambuddy's
  P2S-specific timestamp/probing workaround and remains unavailable here.
- H2C and X2D are dual-nozzle; H2C also has nozzle-rack semantics.
- A2L reports AMS Lite with special wire unit/slot IDs that must not be treated
  as the older A1 mapping.

They are intentionally rejected rather than silently borrowing a superficially
similar profile.

## Slicing and generated package audit

| Target | Model ID | Official printable envelope used | Machine G-code source |
|---|---:|---:|---|
| X1 Carbon | `BL-P001` | 256 x 256 x 250 | Orca 2.2.4 X1C 0.4 |
| X1E | `C13` | 256 x 256 x 250 | Orca 2.2.4 X1E 0.4 |
| P1P | `C11` | 256 x 256 x 250 | Orca 2.2.4 P1P 0.4 |
| P1S | `C12` | 256 x 256 x 250 | Orca 2.2.4 P1S 0.4 |
| A1 | `N2S` | 256 x 256 x 256 | Orca 2.2.4 A1 0.4 |
| A1 Mini | `N1` | 180 x 180 x 180 | Orca 2.2.4 A1 Mini 0.4 |
| H2D | `O1D` | 350 x 320 x 325 | launch-era H2D template + physical-hotend transform |

The review corrected X/P printable Z from 256 to the official 250 mm. X/P
targets also set the official 0..18 x 0..28 mm front-left exclusion in the
native engine. H2D uses the official union bed: left reach X 0..325 and right
reach X 25..350.

Single-nozzle prime towers use 35 mm width. H2D uses a 60 mm shared tower.
Tower placement is clamped to the common reachable envelope with conservative
brim clearance. Snapmaker U1 targeting and templates remain isolated.

Generated projects include the executable plate G-code, uppercase MD5 sidecar,
plate JSON, thumbnails, model/relationship files, project settings, slice info,
printer identity, printable height/area, filament metadata, and nozzle maps.
Before start, the app reopens the actual uploaded local archive and verifies:

- selected plate G-code/JSON/checksum entries exist;
- the MD5 sidecar matches the G-code bytes;
- the G-code machine name matches the selected printer;
- every filament used by the selected plate has a resolved route;
- mappings beyond the declared H2D project filaments are absent;
- known fixed H2D tray/external routes feed the required nozzle;
- dynamic routing is used only with reported FTS hardware and a switchable tray;
- a positively known installed nozzle diameter matches the sliced diameter.

Nozzle mismatch checking is fail-safe like Bambuddy: old firmware or partial
status with no nozzle information remains allowed. When both sides are known,
a mismatch fails before MQTT start. H2D checks the required left/right side,
which is stricter than merely finding the diameter on either hotend.

## AMS and dual-nozzle routing audit

- Regular AMS route `0..127`: flat tray ID and structured
  `{ams_id=route/4, slot_id=route%4}`.
- AMS-HT `128..253`: flat route ID and structured `{ams_id=route, slot_id=0}`.
- Single-nozzle external `254/255`: flat `-1`, structured
  `{ams_id=255, slot_id=0}`; all-explicit-external sets `use_ams=false`.
- H2D external left/right: flat `-1`, structured `ams_id=254/255`.
- An unresolved route remains `-1`/`{255,255}` and is never reinterpreted as
  an external spool. A used unresolved position now fails preflight.
- Any real AMS/AMS-HT route forces `use_ams=true` on single-nozzle printers.
- Mapping length is not capped at five filaments.
- Partial MQTT updates are deep-merged by route so an update from one AMS or
  virtual tray cannot erase the other units, matching Bambuddy's current fix.

Locally generated H2D G-code uses fixed nozzle assignments and declares
`has_filament_switcher=false`; the app does not claim it can generate dynamic
FTS toolpaths. Imported current-Studio projects that already declare dynamic
groups can be sent only when live FTS topology validates them.

## LAN protocol comparison with Bambuddy

| Area | Bambuddy current behavior | App behavior |
|---|---|---|
| MQTT | TLS 8883, `bblp`, MQTT 3.1.1, QoS 1 | same |
| Broker compatibility | some A/P brokers die on request-topic subscription | subscribes only to report topic |
| Session safety | observes command result/state | one command, no blind `project_file` retry |
| Upload | implicit FTPS 990 with family-specific storage paths | H2D root; A/P/X families `/cache/`; A-series protected/clear compatibility sequence |
| Data channel | protected normally; A-series can need clear fallback | same, working A mode cached |
| TLS | self-signed certificate and old firmware quirks | trust-local certificate, TLS 1.2 cap, protected-data session reuse |
| Deadline | minimum 600 s and slow-transfer budget | same |
| Print URL | family/firmware-specific local project URL | H2D `ftp:///filename`; A/P/X `file:///sdcard/cache/filename` |
| Command identity | unique sequence/project/task/subtask IDs | same |
| Calibration | tri-state automatic fields; H2 dual offset calibration | same for implemented models |
| Mapping | `ams_mapping` plus `ams_mapping2` | same |
| Missing acknowledgement | do not blindly repeat | same; matching submission + PREPARE/RUNNING can confirm acceptance |
| Nozzle mismatch | block only positive mismatch | same, with side-aware H2D refinement |

The app uses timestamped remote names instead of deleting an existing remote
file. This avoids Bambuddy's overwrite/delete branch while preventing FTPS 553
collisions.

## Old firmware and secured Developer Mode firmware

Older pre-security firmware often omits authorization status. The app treats
absence as unknown/allowed. A1/A1 Mini firmware through 01.04 uses the archived
minimal A-series `project_file` payload with `/cache/` upload and
`file:///sdcard/cache/` URL; 01.05 and later use the modern command shape. It
does not require a new-only authorization field.

Secured firmware may report Developer Mode through `print.fun` bit
`0x20000000`; a set bit is surfaced as disabled. A/P firmware can omit that
field. Bambuddy sends a nominally no-op `ams_filament_setting` probe in that
case. The app deliberately does not mutate tray metadata merely to test
authorization: it allows monitoring/upload, then converts a `project_file`
“verify failed” response into an explicit Developer Mode/access-code error.

This is compatible but less proactive than Bambuddy: the user may discover
missing Developer Mode at start-command time rather than connection-test time.
The command is still not retried, so there is no duplicate-start risk.

## Camera comparison

- A1/A1 Mini/P1P/P1S use Bambu's authenticated JPEG protocol on port 6000,
  trying TLS and then the older plain transport.
- X1/X1E/H2D use RTSPS on 322 with RTP-over-TCP, the self-signed TLS socket,
  and an SDP filter for H2D's non-standard info lines.
- Bambuddy uses a TLS proxy plus ffmpeg, giving it more tolerance and a
  per-model camera registry. Its P2S profile relaxes probing and regenerates
  timestamps; P2S camera streaming remains intentionally unavailable here.

The Media3 RTSPS path is architecturally different from Bambuddy. The H2D black
frame report means camera confidence must remain low until the exported
diagnostics show `camera_rtsp_state=ready` and a human confirms a live image.

## What is not Bambuddy-equivalent

Bambuddy is a full printer-management server. This Android app implements the
slice/send/print-control subset, not parity with:

- cloud/off-LAN access, printer discovery, job queue and archive management;
- SD-card browsing, timelapse management and finish photos;
- HMS catalog/notifications, fans, chamber controls, lights, speed controls,
  skip-object, energy and detailed sensor history;
- AMS filament editing, K-profile management, spool databases and drying;
- Bambuddy's automatic authorization probe and broker staleness recovery;
- per-nozzle 0.2/0.6/0.8 profiles and current high-flow nozzle variants.

The last item matters for safety: local slicing is currently fixed to standard
0.4 mm. The app now refuses a known installed-size mismatch, but it does not
yet offer a matching alternate nozzle profile.

## Exported diagnostics

The normal diagnostics share action now includes a dedicated **Bambu LAN
timeline (redacted)** plus the underlying recent structured events. It records:

- hashed printer/project IDs, selected model, firmware and Developer Mode;
- MQTT connect/subscribe/pushall/disconnect/reconnect/probe stages;
- state, progress, extruder count, installed nozzle hardware, AMS/AMS-HT/
  external/FTS topology counts;
- FTPS attempt mode, fallback, remote-size recovery, bytes and failure class;
- archive preflight result, plate, mapping, FTS and nozzle decisions;
- project command dispatch, acknowledgement/rejection/timeout and no-retry path;
- TCP-JPEG attempts/first frame and Media3 RTSPS state/errors.

Access codes, full serials, IP endpoints, project filenames and raw MQTT
payloads are not exported. Exception messages are normalized and scrubbed
before being appended.

## Residual risk and safe acceptance sequence

Static/native tests prove configuration, archive structure, G-code generation,
mapping math, parser behavior and command shape. They cannot prove that every
firmware build accepts an app-generated archive or that a particular machine's
installed hardware matches its saved settings.

Validation for v4.0.0:

- focused Bambu transport/parser/preflight/diagnostic regressions: passed;
- full JVM suite: 1,949 tests, 0 failures, 0 errors, 0 skipped;
- full Pixel 8a instrumented suite: 443 tests, 0 failures, 0 errors, 0 skipped;
- manual E2E: 27/27 U1 scenarios, 49/49 Bambu Smoke-7 target scenarios,
  and 2/2 preview lifecycle checks (78/78 total);
- release native library rebuilt with NDK 26, `ninja -j1`, stripped and verified;
- debug APK built and installed only on Pixel 8a `43211JEKB16931` as app version
  4.0.0 (`versionCode=400`);
- user-initiated physical prints completed successfully on A1 Mini and H2D;
  exported Pixel diagnostics showed one successful FTPS upload/command path for
  each, with no retry, fallback, SD-card/STOR failure, crash or ANR.

For an unavailable model, the safe first field test remains:

1. Confirm model and installed 0.4 mm nozzle in printer status.
2. On secured firmware, enable LAN-only Developer Mode and refresh the access code.
3. Slice a small single-filament model and inspect target, bounds and first layer.
4. Use **Upload Only** first and inspect the project on the printer.
5. Export diagnostics if upload, mapping, thumbnail, camera or command behavior differs.
6. Start a physical print only with an owner physically present and explicitly choosing it.
