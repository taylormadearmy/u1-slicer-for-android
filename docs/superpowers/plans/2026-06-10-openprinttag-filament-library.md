# OpenPrintTag Filament Library Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bundle an FFF-only snapshot of the MIT-licensed OpenPrintTag database (~13k filaments) as a searchable Library tab in the filament colour dialogs (pick = slot colour + material, opt-in profile import, favourites + recents), plus a matcher that identifies the exact catalogue filament from the printer's RFID-derived sync data.

**Architecture:** A committed Python conversion script distils the OpenPrintTag YAMLs into one minified JSON asset. A pure-Kotlin `FilamentLibrary` (org.json) parses and searches it; a thin `FilamentLibraryRepository` loads the asset off-main and owns favourites/recents in DataStore. One reusable `FilamentLibraryPicker` composable is hosted by `FilamentColorEditDialog` (opt-in param, AiPaint slot context) and `ExtruderSlotEditDialog` (PrinterScreen). `FilamentLibraryMatcher` (pure) upgrades `PrinterViewModel.syncFilaments()` previews when brand+material+colour confidently match. No native change, no `.so` rebuild.

**Tech Stack:** Kotlin + Jetpack Compose + org.json + DataStore + Room (existing `FilamentProfile`); Python 3 + PyYAML for the build-time converter.

**Spec:** `docs/superpowers/specs/2026-06-10-openprinttag-filament-library-design.md` — the contract. Read it before starting any task.

**Worktree:** `D:\projects\u1-slicer-for-android\.claude\worktrees\filament-library`, branch `feature/filament-library`. All paths below are relative to this root.

---

## Verified codebase facts (do not re-derive; verify only if an edit fails)

- `FilamentColorEditDialog` — `app/src/main/java/com/u1/slicer/ui/FilamentColorEditDialog.kt:31`: `fun FilamentColorEditDialog(initialHex: String, onSave: (String) -> Unit, onDismiss: () -> Unit, onReset: (() -> Unit)? = null)`. Call sites: `AiPaintResultScreen.kt:506` (physical slot — `onSetSlotColor(slot, hex)` → `SlicerViewModel.setSlotColor` → `ExtruderPreset`), `CreateMixSlotDialog.kt:153` (match-target — NOT a slot context), `MainActivity.kt:4420` (Prepare per-file filament override — NOT a slot context; keeps plain HSV).
- `ExtruderSlotEditDialog` — `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt:943` (private): edits a local copy of `ExtruderPreset` (colour via inline `HsvColorPicker`, material dropdown, filament-profile link dropdown), `onSave: (ExtruderPreset) -> Unit` → `PrinterViewModel.updateExtruderPreset`. Opened from `PrinterScreen.kt:747`.
- `ExtruderPreset` — `app/src/main/java/com/u1/slicer/data/ExtruderPreset.kt`: `index`, `color`, `materialType`, `filamentProfileId: Long?`.
- `FilamentProfile` — `app/src/main/java/com/u1/slicer/data/FilamentProfile.kt`: Room entity, non-null `name/material/nozzleTemp/bedTemp/retractLength/retractSpeed`, `color="#808080"`, `density=1.24f` defaults. DAO `app/src/main/java/com/u1/slicer/data/FilamentDao.kt` (`getAll/getById/insert/update/delete`). Add-Filament flow defaults: retractLength `0.8f`, retractSpeed `45f` (FilamentScreen.kt:671-672).
- `SettingsRepository` — `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`: `private object Keys { val X = stringPreferencesKey("x") }`; Flow read via `context.appDataStore.data.map { ... }`; `suspend fun saveX` via `context.appDataStore.edit { ... }`. org.json used for list encoding (see `encodeLibraryMixes` line ~256).
- `ColourMatch.deltaE76(a: String, b: String): Double` — `app/src/main/java/com/u1/slicer/aipaint/ColourMatch.kt:59` (CIE76 on hex strings).
- `MoonrakerClient` — `app/src/main/java/com/u1/slicer/network/MoonrakerClient.kt:299-339` parses `filament_color_rgba/filament_type/filament_sub_type/filament_vendor` into `FilamentSlot(index, label, color, loaded, materialType, subType, manufacturer)` (`PrinterStatus.kt:6-14`). `normalizeMaterialType` (line ~405) already canonicalises printer material strings.
- `PrinterViewModel` — `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt:25`: `AndroidViewModel`, deps via `(application as U1SlicerApplication).container`. `syncFilaments()` (lines 261-284) builds `SyncPreviewEntry(slotIndex, label, currentColor, newColor, currentType, newType)` (lines 123-130); `applySyncResult` (287-308) applies to presets and clears `filamentProfileId` when type applied. `FilamentSyncDialog` in `PrinterScreen.kt:1059-1107` with `SyncEntryRow`.
- `AppContainer` — `app/src/main/java/com/u1/slicer/AppContainer.kt`: manual DI, `val settingsRepository = SettingsRepository(context)`.
- Assets dir: `app/src/main/assets/` (has `bambu_profile_chain/`, `orca_profiles/`, `shaders/`, `bed/`).
- Unit tests: `app/src/test/java/com/u1/slicer/...`, JUnit4 + `org.json:json:20231013` testImplementation present. JVM test cwd = `app/` module dir, so `File("src/main/assets/filament_library.json")` reads the real asset. Structural-guard (source-grep) test precedent: `ui/ModelInfoDialogScrollTest.kt`.
- Settings About section: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt:155` (`SettingsSection("About")` with Version + GitHub rows).
- OpenPrintTag YAML (verified live): material files `data/materials/{brand-slug}/{slug}.yaml` with `slug`, `brand: {slug: ...}`, `name`, `class: FFF|SLA`, `type: PLA|PETG|...` (may be absent on SLA), `primary_color: {color_rgba: '#rrggbbaa'}` (optional), top-level `transmission_distance` and `refractive_index` (optional), `properties: {density, min_print_temperature, max_print_temperature, min_bed_temperature, max_bed_temperature, ...}` (all optional). Brand display names: `data/brands/{slug}.yaml` → `name:`. Default branch `main-pr`. **`snapmaker` is NOT a brand in the database** (stock-firmware tags must gracefully not match).
- Sample entry for contract tests: `prusament-pla-azure-blue` → brand Prusament, name "PLA Azure Blue", type PLA, color_rgba `#008fbeff`, td 5.5, density 1.24, print 205–225, bed 40–60.

## Conventions for every task

- Red-green TDD. **NEVER weaken an assertion to make a test pass.**
- Gradle always with `--no-daemon`. Unit tests: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.SomeTest"` (bash, from worktree root).
- Kotlin file headers: match surrounding style; comments only for non-obvious constraints.
- Commit after each green step with the exact message given. Do not push until the end (and `gh auth switch -u taylormadearmy` before any push).

---

### Task 1: Conversion script (`tools/openprinttag-convert`)

Python script + stdlib-unittest tests against committed fixtures. PyYAML is the only dependency (`pip install pyyaml` — document it; install it if missing).

**Files:**
- Create: `tools/openprinttag-convert/convert.py`
- Create: `tools/openprinttag-convert/README.md`
- Create: `tools/openprinttag-convert/test_convert.py`
- Create fixtures under `tools/openprinttag-convert/fixtures/data/`

- [ ] **Step 1: Write fixtures**

`tools/openprinttag-convert/fixtures/data/brands/testbrand.yaml`:
```yaml
uuid: 00000000-0000-0000-0000-000000000001
slug: testbrand
name: Test Brand
```

`tools/openprinttag-convert/fixtures/data/materials/testbrand/testbrand-pla-azure.yaml` (full-featured FFF):
```yaml
uuid: 00000000-0000-0000-0000-000000000002
slug: testbrand-pla-azure
brand:
  slug: testbrand
name: PLA Azure
class: FFF
type: PLA
primary_color:
  color_rgba: '#008fbeff'
transmission_distance: 5.5
refractive_index: 1.46
properties:
  density: 1.24
  min_print_temperature: 205
  max_print_temperature: 225
  min_bed_temperature: 40
  max_bed_temperature: 60
```

`tools/openprinttag-convert/fixtures/data/materials/testbrand/testbrand-resin-grey.yaml` (SLA — must be filtered out):
```yaml
uuid: 00000000-0000-0000-0000-000000000003
slug: testbrand-resin-grey
brand:
  slug: testbrand
name: Resin Grey
class: SLA
primary_color:
  color_rgba: '#808080ff'
properties:
  density: 1.03
```

`tools/openprinttag-convert/fixtures/data/materials/testbrand/testbrand-pa6-natural.yaml` (no colour + PA6 canonical mapping):
```yaml
uuid: 00000000-0000-0000-0000-000000000004
slug: testbrand-pa6-natural
brand:
  slug: testbrand
name: PA6 Natural
class: FFF
type: PA6
properties:
  density: 1.14
```

- [ ] **Step 2: Write the failing test** — `tools/openprinttag-convert/test_convert.py`:

```python
import json
import os
import unittest

import convert

FIXTURES = os.path.join(os.path.dirname(__file__), "fixtures")


class ConvertTest(unittest.TestCase):
    def setUp(self):
        self.result = convert.convert(FIXTURES, commit="abc1234", date="2026-06-10")

    def test_header(self):
        self.assertEqual(self.result["schema"], 1)
        self.assertEqual(self.result["source"], "OpenPrintTag/openprinttag-database")
        self.assertEqual(self.result["commit"], "abc1234")
        self.assertEqual(self.result["date"], "2026-06-10")
        self.assertEqual(self.result["count"], 2)  # SLA filtered out
        self.assertEqual(len(self.result["entries"]), 2)

    def test_fff_filter_drops_sla(self):
        slugs = [e["s"] for e in self.result["entries"]]
        self.assertNotIn("testbrand-resin-grey", slugs)

    def test_full_entry_fields(self):
        e = next(x for x in self.result["entries"] if x["s"] == "testbrand-pla-azure")
        self.assertEqual(e["b"], "Test Brand")       # display name from brands/
        self.assertEqual(e["n"], "PLA Azure")
        self.assertEqual(e["m"], "PLA")
        self.assertEqual(e["h"], "#008FBE")           # alpha stripped, uppercased
        self.assertEqual(e["td"], 5.5)
        self.assertEqual(e["ri"], 1.46)
        self.assertEqual(e["d"], 1.24)
        self.assertEqual(e["nl"], 205)
        self.assertEqual(e["nh"], 225)
        self.assertEqual(e["bl"], 40)
        self.assertEqual(e["bh"], 60)
        self.assertNotIn("mr", e)                     # canonical == raw → omitted

    def test_no_colour_entry_kept_and_pa6_mapped(self):
        e = next(x for x in self.result["entries"] if x["s"] == "testbrand-pa6-natural")
        self.assertNotIn("h", e)                      # nulls omitted entirely
        self.assertEqual(e["m"], "PA")                # canonical
        self.assertEqual(e["mr"], "PA6")              # raw kept when it differs
        self.assertNotIn("td", e)
        self.assertNotIn("nl", e)
        self.assertEqual(e["d"], 1.14)

    def test_entries_sorted_by_brand_then_name(self):
        entries = self.result["entries"]
        keys = [(x["b"].lower(), x["n"].lower()) for x in entries]
        self.assertEqual(keys, sorted(keys))


if __name__ == "__main__":
    unittest.main()
```

- [ ] **Step 3: Run test to verify it fails**

Run (bash, from worktree root): `python tools/openprinttag-convert/test_convert.py`
Expected: FAIL/ERROR with `ModuleNotFoundError: No module named 'convert'` (or ImportError). If `yaml` is missing later: `pip install pyyaml`.

- [ ] **Step 4: Write `tools/openprinttag-convert/convert.py`**

```python
#!/usr/bin/env python3
"""Distil the OpenPrintTag database (MIT) into the app's bundled filament library asset.

Reads a checkout of github.com/OpenPrintTag/openprinttag-database, keeps class: FFF
materials only, and emits a minified JSON asset. Short keys keep the asset small:
  s=slug  b=brand display name  n=name  m=material (canonical)  mr=raw material when
  it differs  h=#RRGGBB  td=transmission distance  ri=refractive index  d=density
  nl/nh=min/max nozzle temp  bl/bh=min/max bed temp.  Absent values are omitted.

Usage:
  python convert.py --db /path/to/openprinttag-database --out ../../app/src/main/assets/filament_library.json
"""
import argparse
import datetime
import json
import os
import subprocess
import sys

import yaml

# Map OpenPrintTag `type` values onto the app's canonical material set where a
# clean mapping exists. Everything else passes through unchanged (displayable,
# matched conservatively).
CANONICAL = {
    "PA6": "PA", "PA11": "PA", "PA12": "PA", "PA612": "PA", "PA66": "PA", "PPA": "PA",
}


def canonical_material(raw):
    return CANONICAL.get(raw, raw)


def normalise_hex(color_rgba):
    """'#rrggbbaa' or '#rrggbb' -> '#RRGGBB'; None/garbage -> None."""
    if not color_rgba or not isinstance(color_rgba, str):
        return None
    h = color_rgba.strip().lstrip("#")
    if len(h) == 8:
        h = h[:6]
    if len(h) != 6:
        return None
    try:
        int(h, 16)
    except ValueError:
        return None
    return "#" + h.upper()


def load_brands(db_root):
    brands = {}
    brands_dir = os.path.join(db_root, "data", "brands")
    for fn in os.listdir(brands_dir):
        if not fn.endswith(".yaml"):
            continue
        with open(os.path.join(brands_dir, fn), encoding="utf-8") as f:
            doc = yaml.safe_load(f)
        if doc and doc.get("slug"):
            brands[doc["slug"]] = doc.get("name") or doc["slug"]
    return brands


def convert_material(doc, brands):
    """One parsed material YAML -> entry dict, or None if not FFF / unusable."""
    if not doc or doc.get("class") != "FFF":
        return None
    slug = doc.get("slug")
    name = doc.get("name")
    if not slug or not name:
        return None
    brand_slug = (doc.get("brand") or {}).get("slug", "")
    raw_type = doc.get("type") or ""
    material = canonical_material(raw_type) if raw_type else ""
    entry = {
        "s": slug,
        "b": brands.get(brand_slug, brand_slug),
        "n": name,
        "m": material,
    }
    if raw_type and raw_type != material:
        entry["mr"] = raw_type
    hexcol = normalise_hex((doc.get("primary_color") or {}).get("color_rgba"))
    if hexcol:
        entry["h"] = hexcol
    if isinstance(doc.get("transmission_distance"), (int, float)):
        entry["td"] = doc["transmission_distance"]
    if isinstance(doc.get("refractive_index"), (int, float)):
        entry["ri"] = doc["refractive_index"]
    props = doc.get("properties") or {}
    if isinstance(props.get("density"), (int, float)):
        entry["d"] = props["density"]
    for src, key in (
        ("min_print_temperature", "nl"), ("max_print_temperature", "nh"),
        ("min_bed_temperature", "bl"), ("max_bed_temperature", "bh"),
    ):
        v = props.get(src)
        if isinstance(v, (int, float)):
            entry[key] = int(round(v))
    return entry


def convert(db_root, commit, date):
    brands = load_brands(db_root)
    materials_dir = os.path.join(db_root, "data", "materials")
    entries = []
    for brand_dir in sorted(os.listdir(materials_dir)):
        full = os.path.join(materials_dir, brand_dir)
        if not os.path.isdir(full):
            continue
        for fn in sorted(os.listdir(full)):
            if not fn.endswith(".yaml"):
                continue
            with open(os.path.join(full, fn), encoding="utf-8") as f:
                doc = yaml.safe_load(f)
            entry = convert_material(doc, brands)
            if entry:
                entries.append(entry)
    entries.sort(key=lambda e: (e["b"].lower(), e["n"].lower()))
    return {
        "schema": 1,
        "source": "OpenPrintTag/openprinttag-database",
        "commit": commit,
        "date": date,
        "count": len(entries),
        "entries": entries,
    }


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--db", required=True, help="path to openprinttag-database checkout")
    ap.add_argument("--out", required=True, help="output JSON path")
    ap.add_argument("--commit", default=None, help="database commit SHA (default: git rev-parse in --db)")
    args = ap.parse_args()
    commit = args.commit or subprocess.check_output(
        ["git", "-C", args.db, "rev-parse", "--short", "HEAD"], text=True).strip()
    date = datetime.date.today().isoformat()
    result = convert(args.db, commit=commit, date=date)
    with open(args.out, "w", encoding="utf-8", newline="\n") as f:
        json.dump(result, f, separators=(",", ":"), ensure_ascii=False)
    size_mb = os.path.getsize(args.out) / 1e6
    print(f"Wrote {result['count']} FFF entries ({size_mb:.2f} MB) to {args.out} "
          f"[commit {commit}, {date}]")


if __name__ == "__main__":
    main()
```

- [ ] **Step 5: Run test to verify it passes**

Run: `python tools/openprinttag-convert/test_convert.py`
Expected: `OK` (5 tests).

- [ ] **Step 6: Write `tools/openprinttag-convert/README.md`**

```markdown
# openprinttag-convert

Distils the [OpenPrintTag database](https://github.com/OpenPrintTag/openprinttag-database)
(MIT) into `app/src/main/assets/filament_library.json` — the FFF-only filament
library bundled with the app.

## Refresh the snapshot (release-flow step)

```bash
pip install pyyaml                       # one-time
git clone --depth 1 https://github.com/OpenPrintTag/openprinttag-database /tmp/optdb
python tools/openprinttag-convert/convert.py \
  --db /tmp/optdb \
  --out app/src/main/assets/filament_library.json
```

The snapshot commit SHA + date are stamped in the JSON header and surfaced in
the app (Library tab footer / Settings ▸ About). Commit the regenerated asset.

## Tests

```bash
python tools/openprinttag-convert/test_convert.py
```

Fixtures under `fixtures/` are hand-written samples mirroring the real schema
(FFF with full data, SLA to be filtered, colour-less PA6 for canonical mapping).
```

- [ ] **Step 7: Commit**

```bash
git add tools/openprinttag-convert
git commit -m "feat(library): OpenPrintTag conversion script + fixture tests"
```

---

### Task 2: Generate the real snapshot asset + NOTICE

**Files:**
- Create: `app/src/main/assets/filament_library.json` (generated)
- Create: `app/src/main/assets/filament_library.NOTICE.txt`

- [ ] **Step 1: Clone the database and run the converter**

```bash
rm -rf /tmp/optdb && git clone --depth 1 https://github.com/OpenPrintTag/openprinttag-database /tmp/optdb
pip show pyyaml >/dev/null 2>&1 || pip install pyyaml
python tools/openprinttag-convert/convert.py --db /tmp/optdb --out app/src/main/assets/filament_library.json
```

Expected output: `Wrote <N> FFF entries (<M> MB) ...` with **N > 10000** and **M < 3.5 MB**. If N ≤ 10000 or any traceback: STOP and investigate (clone incomplete? schema drift?) — do not commit a partial asset.

- [ ] **Step 2: Sanity-check the asset**

```bash
python - <<'EOF'
import json
d = json.load(open("app/src/main/assets/filament_library.json", encoding="utf-8"))
assert d["schema"] == 1 and d["count"] == len(d["entries"]) and d["count"] > 10000
brands = {e["b"] for e in d["entries"]}
assert len(brands) >= 100, len(brands)
azure = next(e for e in d["entries"] if e["s"] == "prusament-pla-azure-blue")
assert azure["b"] == "Prusament" and azure["m"] == "PLA" and azure["h"] == "#008FBE", azure
assert all(e["s"] and e["b"] and e["n"] for e in d["entries"])
print("asset OK:", d["count"], "entries,", len(brands), "brands, commit", d["commit"], d["date"])
EOF
```

Expected: `asset OK: ...`.

- [ ] **Step 3: Write `app/src/main/assets/filament_library.NOTICE.txt`**

```text
filament_library.json is generated from the OpenPrintTag database
(https://github.com/OpenPrintTag/openprinttag-database), licensed under the
MIT License. Copyright (c) OpenPrintTag contributors.

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

Snapshot details (commit SHA + date) are embedded in the JSON header. See
tools/openprinttag-convert/README.md for the refresh procedure.
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/assets/filament_library.json app/src/main/assets/filament_library.NOTICE.txt
git commit -m "feat(library): bundle FFF-only OpenPrintTag snapshot asset + MIT NOTICE"
```

---

### Task 3: `FilamentLibrary` — parse, search, entry, snapshot info (pure Kotlin)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/FilamentLibrary.kt`
- Test: `app/src/test/java/com/u1/slicer/data/FilamentLibraryTest.kt`
- Test: `app/src/test/java/com/u1/slicer/data/FilamentLibraryAssetContractTest.kt`

- [ ] **Step 1: Write the failing tests** — `FilamentLibraryTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryTest {

    private fun lib() = FilamentLibrary.parse(
        """
        {"schema":1,"source":"OpenPrintTag/openprinttag-database","commit":"abc1234","date":"2026-06-10","count":5,
         "entries":[
          {"s":"acme-pla-red","b":"Acme","n":"PLA Red","m":"PLA","h":"#FF0000","td":2.0,"d":1.24,"nl":205,"nh":225,"bl":40,"bh":60},
          {"s":"acme-pla-blue","b":"Acme","n":"PLA Blue","m":"PLA","h":"#0000FF"},
          {"s":"acme-petg-red","b":"Acme","n":"PETG Red","m":"PETG","h":"#EE0000"},
          {"s":"bolt-pa6-nat","b":"Bolt","n":"PA6 Natural","m":"PA","mr":"PA6","d":1.14},
          {"s":"bolt-pla-red","b":"Bolt","n":"PLA Cherry Red","m":"PLA","h":"#F10505","ri":1.46}
         ]}
        """.trimIndent()
    )

    @Test
    fun `parse exposes snapshot info and entries`() {
        val l = lib()
        assertEquals(5, l.entries.size)
        assertEquals("abc1234", l.snapshot.commit)
        assertEquals("2026-06-10", l.snapshot.date)
        assertEquals(5, l.snapshot.count)
    }

    @Test
    fun `parse maps optional fields, nulls when absent`() {
        val red = lib().entry("acme-pla-red")!!
        assertEquals("Acme", red.brand)
        assertEquals("#FF0000", red.hex)
        assertEquals(2.0, red.td!!, 1e-9)
        assertEquals(205, red.minNozzle)
        assertEquals(225, red.maxNozzle)
        assertEquals(40, red.minBed)
        assertEquals(60, red.maxBed)
        assertNull(red.ri)
        val nat = lib().entry("bolt-pa6-nat")!!
        assertNull(nat.hex)
        assertEquals("PA", nat.material)
        assertEquals("PA6", nat.materialRaw)
        assertNull(nat.td)
        assertNull(nat.minNozzle)
    }

    @Test
    fun `entry returns null for unknown slug`() {
        assertNull(lib().entry("nope"))
    }

    @Test
    fun `blank query lists favourites then recents then rest alphabetical`() {
        val res = lib().search(
            "", material = null,
            favourites = setOf("bolt-pla-red"),
            recents = listOf("acme-petg-red", "bolt-pla-red"),
        )
        assertEquals("bolt-pla-red", res[0].slug)        // favourite first
        assertEquals("acme-petg-red", res[1].slug)       // recent (favourites not repeated)
        // rest alphabetical by brand+name
        assertEquals(listOf("acme-pla-blue", "acme-pla-red", "bolt-pa6-nat"), res.drop(2).map { it.slug })
    }

    @Test
    fun `query matches across brand name material, all tokens must match`() {
        val res = lib().search("acme red")
        assertEquals(setOf("acme-pla-red", "acme-petg-red"), res.map { it.slug }.toSet())
        assertTrue(lib().search("acme red petg").map { it.slug } == listOf("acme-petg-red"))
    }

    @Test
    fun `query is case-insensitive`() {
        assertEquals(listOf("bolt-pla-red"), lib().search("CHERRY").map { it.slug })
    }

    @Test
    fun `material filter applies to blank and non-blank queries`() {
        assertEquals(setOf("acme-pla-red", "acme-pla-blue", "bolt-pla-red"),
            lib().search("", material = "PLA").map { it.slug }.toSet())
        assertEquals(listOf("acme-petg-red"), lib().search("red", material = "PETG").map { it.slug })
    }

    @Test
    fun `favourites rank first on non-blank query`() {
        val res = lib().search("red", favourites = setOf("bolt-pla-red"))
        assertEquals("bolt-pla-red", res[0].slug)
    }

    @Test
    fun `limit caps results`() {
        assertEquals(2, lib().search("", limit = 2).size)
    }

    @Test
    fun `parse rejects malformed json`() {
        try {
            FilamentLibrary.parse("{not json")
            org.junit.Assert.fail("expected exception")
        } catch (_: Exception) { /* expected */ }
    }

    @Test
    fun `displayName is brand plus name`() {
        assertEquals("Acme PLA Red", lib().entry("acme-pla-red")!!.displayName)
    }
}
```

`FilamentLibraryAssetContractTest.kt` (guards the real bundled asset against parser drift):

```kotlin
package com.u1.slicer.data

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryAssetContractTest {

    private val asset = File("src/main/assets/filament_library.json")

    @Test
    fun `bundled asset exists and parses with expected scale`() {
        assertTrue("asset missing — run tools/openprinttag-convert", asset.exists())
        val lib = FilamentLibrary.parse(asset.readText())
        assertTrue("expected >10000 FFF entries, got ${lib.entries.size}", lib.entries.size > 10000)
        assertEquals(lib.snapshot.count, lib.entries.size)
        assertTrue(lib.snapshot.commit.isNotBlank())
        assertTrue(lib.snapshot.date.matches(Regex("""\d{4}-\d{2}-\d{2}""")))
        assertTrue("expected >=100 brands", lib.entries.map { it.brand }.toSet().size >= 100)
    }

    @Test
    fun `every entry has slug brand name and well-formed hex when present`() {
        val lib = FilamentLibrary.parse(asset.readText())
        lib.entries.forEach { e ->
            assertTrue(e.slug.isNotBlank()); assertTrue(e.brand.isNotBlank()); assertTrue(e.name.isNotBlank())
            e.hex?.let { assertTrue("bad hex $it on ${e.slug}", it.matches(Regex("#[0-9A-F]{6}"))) }
        }
    }

    @Test
    fun `known prusament entry round-trips`() {
        val lib = FilamentLibrary.parse(asset.readText())
        val azure = lib.entry("prusament-pla-azure-blue")!!
        assertEquals("Prusament", azure.brand)
        assertEquals("PLA", azure.material)
        assertEquals("#008FBE", azure.hex)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryTest" --tests "com.u1.slicer.data.FilamentLibraryAssetContractTest"`
Expected: compilation FAILURE (`FilamentLibrary` unresolved).

- [ ] **Step 3: Implement `FilamentLibrary.kt`**

```kotlin
package com.u1.slicer.data

import org.json.JSONObject

/** One filament from the bundled OpenPrintTag snapshot (FFF only). */
data class FilamentLibraryEntry(
    val slug: String,
    val brand: String,
    val name: String,
    /** Canonical material where mappable (e.g. PA6→PA); raw type otherwise. */
    val material: String,
    /** Original database type when it differs from [material]. */
    val materialRaw: String? = null,
    /** "#RRGGBB", or null for entries without a primary colour (no swatch). */
    val hex: String? = null,
    /** HueForge transmission distance — carried for future translucency work, NOT used in slicing. */
    val td: Double? = null,
    /** Refractive index — carried for future translucency work, NOT used in slicing. */
    val ri: Double? = null,
    val density: Double? = null,
    val minNozzle: Int? = null,
    val maxNozzle: Int? = null,
    val minBed: Int? = null,
    val maxBed: Int? = null,
) {
    val displayName: String get() = "$brand $name"
}

data class LibrarySnapshotInfo(val commit: String, val date: String, val count: Int)

/**
 * In-memory filament library parsed from assets/filament_library.json.
 * Pure Kotlin — hosts load the asset text and call [parse]; search inputs
 * (favourites/recents) are passed in so this class stays state-free.
 */
class FilamentLibrary(
    val entries: List<FilamentLibraryEntry>,
    val snapshot: LibrarySnapshotInfo,
) {
    private val bySlug = entries.associateBy { it.slug }

    fun entry(slug: String): FilamentLibraryEntry? = bySlug[slug]

    fun search(
        query: String,
        material: String? = null,
        favourites: Set<String> = emptySet(),
        recents: List<String> = emptyList(),
        limit: Int = DEFAULT_LIMIT,
    ): List<FilamentLibraryEntry> {
        val pool = if (material == null) entries
        else entries.filter { it.material.equals(material, ignoreCase = true) }

        val q = query.trim()
        if (q.isEmpty()) {
            val favs = pool.filter { it.slug in favourites }
                .sortedBy { it.displayName.lowercase() }
            val recs = recents.mapNotNull { slug ->
                pool.firstOrNull { it.slug == slug && slug !in favourites }
            }
            val head = (favs + recs)
            val headSlugs = head.map { it.slug }.toSet()
            val rest = pool.filter { it.slug !in headSlugs }
                .sortedBy { it.displayName.lowercase() }
            return (head + rest).take(limit)
        }

        val tokens = q.lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        val matched = pool.mapNotNull { e ->
            val haystack = "${e.brand} ${e.name} ${e.material} ${e.materialRaw ?: ""}".lowercase()
            if (tokens.all { haystack.contains(it) }) {
                val quality = when {
                    e.displayName.lowercase().startsWith(q.lowercase()) -> 0
                    haystack.split(' ').any { w -> w.startsWith(tokens.first()) } -> 1
                    else -> 2
                }
                val favRank = if (e.slug in favourites) 0 else 1
                Triple(e, favRank, quality)
            } else null
        }
        return matched
            .sortedWith(compareBy({ it.second }, { it.third }, { it.first.displayName.lowercase() }))
            .map { it.first }
            .take(limit)
    }

    companion object {
        const val DEFAULT_LIMIT = 200

        /** Throws on malformed input — callers map exceptions to a Failed state. */
        fun parse(json: String): FilamentLibrary {
            val root = JSONObject(json)
            val arr = root.getJSONArray("entries")
            val entries = ArrayList<FilamentLibraryEntry>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(
                    FilamentLibraryEntry(
                        slug = o.getString("s"),
                        brand = o.getString("b"),
                        name = o.getString("n"),
                        material = o.optString("m", ""),
                        materialRaw = if (o.has("mr")) o.getString("mr") else null,
                        hex = if (o.has("h")) o.getString("h") else null,
                        td = if (o.has("td")) o.getDouble("td") else null,
                        ri = if (o.has("ri")) o.getDouble("ri") else null,
                        density = if (o.has("d")) o.getDouble("d") else null,
                        minNozzle = if (o.has("nl")) o.getInt("nl") else null,
                        maxNozzle = if (o.has("nh")) o.getInt("nh") else null,
                        minBed = if (o.has("bl")) o.getInt("bl") else null,
                        maxBed = if (o.has("bh")) o.getInt("bh") else null,
                    )
                )
            }
            return FilamentLibrary(
                entries = entries,
                snapshot = LibrarySnapshotInfo(
                    commit = root.optString("commit", "?"),
                    date = root.optString("date", "?"),
                    count = root.optInt("count", entries.size),
                ),
            )
        }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryTest" --tests "com.u1.slicer.data.FilamentLibraryAssetContractTest"`
Expected: PASS (14 tests). If a ranking test fails, fix `search` — do not change the expected ordering.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/FilamentLibrary.kt app/src/test/java/com/u1/slicer/data/FilamentLibraryTest.kt app/src/test/java/com/u1/slicer/data/FilamentLibraryAssetContractTest.kt
git commit -m "feat(library): FilamentLibrary parse/search/entry + asset contract tests"
```

---

### Task 4: Favourites/recents persistence + `FilamentLibraryRepository` + DI

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/data/SettingsRepository.kt`
- Create: `app/src/main/java/com/u1/slicer/data/FilamentLibraryRepository.kt`
- Modify: `app/src/main/java/com/u1/slicer/AppContainer.kt`
- Test: `app/src/test/java/com/u1/slicer/data/LibrarySlugListCodecTest.kt`
- Test: `app/src/test/java/com/u1/slicer/data/FilamentLibraryRecentsTest.kt`

- [ ] **Step 1: Write the failing tests**

`LibrarySlugListCodecTest.kt` (pure codec, lives in SettingsRepository's file as top-level internal functions):

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class LibrarySlugListCodecTest {
    @Test
    fun `round trip preserves order and content`() {
        val slugs = listOf("a-1", "b-2", "c-3")
        assertEquals(slugs, decodeSlugList(encodeSlugList(slugs)))
    }

    @Test
    fun `empty and blank decode to empty list`() {
        assertEquals(emptyList<String>(), decodeSlugList(""))
        assertEquals(emptyList<String>(), decodeSlugList("   "))
    }

    @Test
    fun `malformed json decodes to empty list`() {
        assertEquals(emptyList<String>(), decodeSlugList("{broken"))
    }
}
```

`FilamentLibraryRecentsTest.kt` (pure recents-update helper):

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Test

class FilamentLibraryRecentsTest {
    @Test
    fun `new slug goes first`() {
        assertEquals(listOf("c", "a", "b"), updateRecents(listOf("a", "b"), "c"))
    }

    @Test
    fun `existing slug moves to front without duplicate`() {
        assertEquals(listOf("b", "a", "c"), updateRecents(listOf("a", "b", "c"), "b"))
    }

    @Test
    fun `capped at MAX_RECENTS`() {
        val full = (1..FilamentLibraryRepository.MAX_RECENTS).map { "s$it" }
        val out = updateRecents(full, "new")
        assertEquals(FilamentLibraryRepository.MAX_RECENTS, out.size)
        assertEquals("new", out.first())
        assertEquals(false, out.contains("s${FilamentLibraryRepository.MAX_RECENTS}"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.LibrarySlugListCodecTest" --tests "com.u1.slicer.data.FilamentLibraryRecentsTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement**

In `SettingsRepository.kt` — add to `Keys`:
```kotlin
val FILAMENT_LIBRARY_FAVOURITES = stringPreferencesKey("filament_library_favourites")
val FILAMENT_LIBRARY_RECENTS = stringPreferencesKey("filament_library_recents")
```
Add flows + setters (same pattern as existing keys):
```kotlin
val filamentLibraryFavourites: Flow<List<String>> = context.appDataStore.data.map { prefs ->
    decodeSlugList(prefs[Keys.FILAMENT_LIBRARY_FAVOURITES] ?: "")
}

val filamentLibraryRecents: Flow<List<String>> = context.appDataStore.data.map { prefs ->
    decodeSlugList(prefs[Keys.FILAMENT_LIBRARY_RECENTS] ?: "")
}

suspend fun setFilamentLibraryFavourites(slugs: List<String>) {
    context.appDataStore.edit { prefs ->
        prefs[Keys.FILAMENT_LIBRARY_FAVOURITES] = encodeSlugList(slugs)
    }
}

suspend fun setFilamentLibraryRecents(slugs: List<String>) {
    context.appDataStore.edit { prefs ->
        prefs[Keys.FILAMENT_LIBRARY_RECENTS] = encodeSlugList(slugs)
    }
}
```
Top-level internal codec functions (bottom of `SettingsRepository.kt`, next to the existing mix codecs):
```kotlin
internal fun encodeSlugList(slugs: List<String>): String =
    org.json.JSONArray().apply { slugs.forEach { put(it) } }.toString()

internal fun decodeSlugList(json: String): List<String> {
    if (json.isBlank()) return emptyList()
    return try {
        val arr = org.json.JSONArray(json)
        (0 until arr.length()).map { arr.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }
}
```

Create `FilamentLibraryRepository.kt`:
```kotlin
package com.u1.slicer.data

import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

sealed class LibraryState {
    object Loading : LibraryState()
    data class Ready(val library: FilamentLibrary) : LibraryState()
    data class Failed(val message: String) : LibraryState()
}

/**
 * Owns the bundled filament library: lazy one-shot asset load off the main
 * thread, plus favourites/recents persistence (slug lists in DataStore).
 */
class FilamentLibraryRepository(
    private val context: Context,
    private val settings: SettingsRepository,
) {
    private val _state = MutableStateFlow<LibraryState>(LibraryState.Loading)
    val state: StateFlow<LibraryState> = _state.asStateFlow()
    private val loadStarted = AtomicBoolean(false)

    val favourites: Flow<List<String>> = settings.filamentLibraryFavourites
    val recents: Flow<List<String>> = settings.filamentLibraryRecents

    /** Idempotent — first caller wins; later calls are no-ops unless retrying a failure. */
    fun ensureLoaded(scope: CoroutineScope) {
        if (!loadStarted.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) { load() }
    }

    fun retry(scope: CoroutineScope) {
        _state.value = LibraryState.Loading
        scope.launch(Dispatchers.IO) { load() }
    }

    private fun load() {
        _state.value = try {
            val text = context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
            LibraryState.Ready(FilamentLibrary.parse(text))
        } catch (e: Exception) {
            LibraryState.Failed(e.message ?: "Failed to load filament library")
        }
    }

    suspend fun toggleFavourite(slug: String) {
        val current = settings.filamentLibraryFavourites.first()
        val updated = if (slug in current) current - slug else current + slug
        settings.setFilamentLibraryFavourites(updated)
    }

    suspend fun recordRecent(slug: String) {
        settings.setFilamentLibraryRecents(updateRecents(settings.filamentLibraryRecents.first(), slug))
    }

    companion object {
        const val ASSET_NAME = "filament_library.json"
        const val MAX_RECENTS = 10
    }
}

/** Most-recent-first, deduplicated, capped at [FilamentLibraryRepository.MAX_RECENTS]. */
internal fun updateRecents(current: List<String>, slug: String): List<String> =
    (listOf(slug) + current.filterNot { it == slug }).take(FilamentLibraryRepository.MAX_RECENTS)
```

In `AppContainer.kt`, after `settingsRepository`:
```kotlin
val filamentLibraryRepository = FilamentLibraryRepository(context, settingsRepository)
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.LibrarySlugListCodecTest" --tests "com.u1.slicer.data.FilamentLibraryRecentsTest"`
Expected: PASS (6 tests). Also run `./gradlew assembleDebug --no-daemon` to confirm the app module still compiles.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/SettingsRepository.kt app/src/main/java/com/u1/slicer/data/FilamentLibraryRepository.kt app/src/main/java/com/u1/slicer/AppContainer.kt app/src/test/java/com/u1/slicer/data/LibrarySlugListCodecTest.kt app/src/test/java/com/u1/slicer/data/FilamentLibraryRecentsTest.kt
git commit -m "feat(library): favourites/recents persistence + FilamentLibraryRepository + DI"
```

---

### Task 5: Import mapping helpers (pure) — preview rows + profile upsert mapping

Pure functions consumed by the import-preview dialog (Task 7/8). Done before UI so the UI tasks only wire.

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/FilamentLibraryImport.kt`
- Test: `app/src/test/java/com/u1/slicer/data/FilamentLibraryImportTest.kt`

- [ ] **Step 1: Write the failing tests** — `FilamentLibraryImportTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryImportTest {

    private val full = FilamentLibraryEntry(
        slug = "acme-pla-red", brand = "Acme", name = "PLA Red", material = "PLA",
        hex = "#FF0000", td = 2.5, ri = 1.46, density = 1.24,
        minNozzle = 205, maxNozzle = 225, minBed = 40, maxBed = 60,
    )
    private val colourOnly = FilamentLibraryEntry(
        slug = "acme-pla-blue", brand = "Acme", name = "PLA Blue", material = "PLA", hex = "#0000FF",
    )

    @Test
    fun `hasImportableData true only when fields beyond colour and material exist`() {
        assertTrue(hasImportableData(full))
        assertFalse(hasImportableData(colourOnly))
        assertTrue(hasImportableData(colourOnly.copy(density = 1.2)))
        assertTrue(hasImportableData(colourOnly.copy(td = 1.0)))
    }

    @Test
    fun `preview rows list only present fields with units`() {
        val rows = buildImportPreview(full)
        val labels = rows.map { it.label }
        assertEquals(
            listOf("Nozzle temperature", "Bed temperature", "Density",
                "Transmission distance", "Refractive index"),
            labels
        )
        assertEquals("205–225 °C", rows[0].value)
        assertEquals("40–60 °C", rows[1].value)
        assertEquals("1.24 g/cm³", rows[2].value)
        assertEquals(FUTURE_TRANSLUCENCY_NOTE, rows[3].note)
        assertEquals(FUTURE_TRANSLUCENCY_NOTE, rows[4].note)
        assertNull(rows[0].note)
    }

    @Test
    fun `preview handles single-ended temperature ranges`() {
        val rows = buildImportPreview(full.copy(maxNozzle = null, minBed = null))
        assertEquals("205 °C", rows.first { it.label == "Nozzle temperature" }.value)
        assertEquals("60 °C", rows.first { it.label == "Bed temperature" }.value)
    }

    @Test
    fun `preview empty for colour-only entry`() {
        assertTrue(buildImportPreview(colourOnly).isEmpty())
    }

    @Test
    fun `profile mapping uses midpoints and entry colour`() {
        val p = libraryEntryToProfile(full, existing = null)
        assertEquals("Acme PLA Red", p.name)
        assertEquals("PLA", p.material)
        assertEquals(215, p.nozzleTemp)   // midpoint 205..225
        assertEquals(50, p.bedTemp)       // midpoint 40..60
        assertEquals(1.24f, p.density, 1e-4f)
        assertEquals("#FF0000", p.color)
        assertEquals(0.8f, p.retractLength, 1e-4f)
        assertEquals(45f, p.retractSpeed, 1e-4f)
        assertEquals(0L, p.id)
    }

    @Test
    fun `profile mapping falls back to material defaults when temps absent`() {
        val p = libraryEntryToProfile(colourOnly, existing = null)
        assertEquals(220, p.nozzleTemp)   // PLA default
        assertEquals(60, p.bedTemp)
        val petg = libraryEntryToProfile(colourOnly.copy(material = "PETG"), existing = null)
        assertEquals(235, petg.nozzleTemp)
        assertEquals(70, petg.bedTemp)
    }

    @Test
    fun `re-import updates the existing profile in place keeping its id`() {
        val existing = libraryEntryToProfile(full, existing = null).copy(id = 42L)
        val updated = libraryEntryToProfile(full.copy(minNozzle = 210, maxNozzle = 230), existing = existing)
        assertEquals(42L, updated.id)
        assertEquals(220, updated.nozzleTemp)
        assertEquals("Acme PLA Red", updated.name)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryImportTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `FilamentLibraryImport.kt`**

```kotlin
package com.u1.slicer.data

/** Shown on TD / refractive-index preview rows — these fields do not affect slicing. */
const val FUTURE_TRANSLUCENCY_NOTE = "For future translucency features — not used in slicing"

data class ImportPreviewRow(val label: String, val value: String, val note: String? = null)

/** True when the entry carries anything beyond colour + material worth importing. */
fun hasImportableData(e: FilamentLibraryEntry): Boolean =
    e.minNozzle != null || e.maxNozzle != null || e.minBed != null || e.maxBed != null ||
        e.density != null || e.td != null || e.ri != null

private fun rangeText(lo: Int?, hi: Int?): String? = when {
    lo != null && hi != null && lo != hi -> "$lo–$hi °C"
    lo != null -> "$lo °C"
    hi != null -> "$hi °C"
    else -> null
}

/** Field-by-field list of exactly what an import would bring in — present fields only. */
fun buildImportPreview(e: FilamentLibraryEntry): List<ImportPreviewRow> {
    val rows = mutableListOf<ImportPreviewRow>()
    rangeText(e.minNozzle, e.maxNozzle)?.let { rows.add(ImportPreviewRow("Nozzle temperature", it)) }
    rangeText(e.minBed, e.maxBed)?.let { rows.add(ImportPreviewRow("Bed temperature", it)) }
    e.density?.let { rows.add(ImportPreviewRow("Density", "$it g/cm³")) }
    e.td?.let { rows.add(ImportPreviewRow("Transmission distance", "$it", FUTURE_TRANSLUCENCY_NOTE)) }
    e.ri?.let { rows.add(ImportPreviewRow("Refractive index", "$it", FUTURE_TRANSLUCENCY_NOTE)) }
    return rows
}

private fun midpoint(lo: Int?, hi: Int?): Int? = when {
    lo != null && hi != null -> (lo + hi) / 2
    lo != null -> lo
    hi != null -> hi
    else -> null
}

private fun defaultNozzleFor(material: String): Int = when (material.uppercase()) {
    "PETG" -> 235
    "ABS", "ASA" -> 250
    else -> 220
}

private fun defaultBedFor(material: String): Int = when (material.uppercase()) {
    "PETG" -> 70
    "ABS", "ASA" -> 90
    "TPU" -> 50
    else -> 60
}

/**
 * Map a library entry to a [FilamentProfile]. Re-imports update the existing
 * profile in place (same id, same name) so no duplicates accumulate — lookup
 * is by exact profile name "<brand> <name>" (see FilamentDao.getByName).
 */
fun libraryEntryToProfile(e: FilamentLibraryEntry, existing: FilamentProfile?): FilamentProfile {
    val base = existing ?: FilamentProfile(
        name = e.displayName,
        material = e.material,
        nozzleTemp = defaultNozzleFor(e.material),
        bedTemp = defaultBedFor(e.material),
        retractLength = 0.8f,
        retractSpeed = 45f,
    )
    return base.copy(
        name = e.displayName,
        material = e.material,
        nozzleTemp = midpoint(e.minNozzle, e.maxNozzle) ?: base.nozzleTemp,
        bedTemp = midpoint(e.minBed, e.maxBed) ?: base.bedTemp,
        density = e.density?.toFloat() ?: base.density,
        color = e.hex ?: base.color,
    )
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryImportTest"`
Expected: PASS (7 tests).

- [ ] **Step 5: Add `getByName` to `FilamentDao`** (used by upsert in Task 7) — in `app/src/main/java/com/u1/slicer/data/FilamentDao.kt` add:

```kotlin
@Query("SELECT * FROM filament_profiles WHERE name = :name LIMIT 1")
suspend fun getByName(name: String): FilamentProfile?
```

Run: `./gradlew assembleDebug --no-daemon` — expected BUILD SUCCESSFUL (Room validates the query at compile time).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/FilamentLibraryImport.kt app/src/main/java/com/u1/slicer/data/FilamentDao.kt app/src/test/java/com/u1/slicer/data/FilamentLibraryImportTest.kt
git commit -m "feat(library): import preview mapping + profile upsert mapping + DAO getByName"
```

---

### Task 6: `FilamentLibraryPicker` composable + import preview dialog

The reusable picker UI plus the import-preview dialog, with structural guard tests (the project has no Compose UI test harness — source-grep guards follow the `ModelInfoDialogScrollTest` precedent).

**Files:**
- Create: `app/src/main/java/com/u1/slicer/ui/FilamentLibraryPicker.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/FilamentLibraryPickerStructuralTest.kt`

- [ ] **Step 1: Write the failing structural test** — `FilamentLibraryPickerStructuralTest.kt`:

```kotlin
package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards for FilamentLibraryPicker (no Compose UI harness in project).
 * Pins: search via FilamentLibrary.search, material filter chips, favourites star,
 * Failed-state retry, snapshot footer, and the import affordance gated on
 * hasImportableData.
 */
class FilamentLibraryPickerStructuralTest {

    private val src = File("src/main/java/com/u1/slicer/ui/FilamentLibraryPicker.kt").readText()

    @Test
    fun `picker searches through FilamentLibrary search with favourites and recents`() {
        assertTrue(src.contains(".search("))
        assertTrue(src.contains("favourites"))
        assertTrue(src.contains("recents"))
    }

    @Test
    fun `picker offers material filter chips`() {
        assertTrue(src.contains("FilterChip"))
        listOf("\"PLA\"", "\"PETG\"", "\"ABS\"", "\"TPU\"", "\"ASA\"").forEach { m ->
            assertTrue("missing material chip $m", src.contains(m))
        }
    }

    @Test
    fun `picker renders failed state with retry`() {
        assertTrue(src.contains("LibraryState.Failed"))
        assertTrue(src.contains("onRetry"))
    }

    @Test
    fun `import affordance is gated on hasImportableData`() {
        assertTrue(src.contains("hasImportableData"))
    }

    @Test
    fun `import preview dialog lists rows from buildImportPreview`() {
        assertTrue(src.contains("buildImportPreview"))
        assertTrue(src.contains("FilamentImportPreviewDialog"))
    }

    @Test
    fun `snapshot info shown in footer`() {
        assertTrue(src.contains("snapshot"))
    }

    @Test
    fun `star toggle wired`() {
        assertTrue(src.contains("onToggleFavourite"))
        assertTrue(src.contains("Icons.Default.Star") || src.contains("Icons.Filled.Star"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.ui.FilamentLibraryPickerStructuralTest"`
Expected: FAIL (`FileNotFoundException` — picker file doesn't exist).

- [ ] **Step 3: Implement `FilamentLibraryPicker.kt`**

Public API (the implementer fills in standard Compose layout following the app's Material3 style — `OutlinedTextField` search, `FilterChip` row, `LazyColumn` rows with 24.dp colour swatch circle / outlined circle when `hex == null`, brand+name primary text, material as `labelSmall`, trailing star `IconButton`):

```kotlin
package com.u1.slicer.ui

// imports: androidx.compose.*, com.u1.slicer.data.* (FilamentLibrary, FilamentLibraryEntry,
// LibraryState, hasImportableData, buildImportPreview, ImportPreviewRow)

/**
 * Reusable searchable filament library (OpenPrintTag snapshot).
 * Hosted as a tab by FilamentColorEditDialog (slot contexts) and
 * ExtruderSlotEditDialog. State is hoisted — this composable owns only
 * the query/filter/selection UI state.
 *
 * onPick     — apply colour+material to the slot (host closes itself).
 * onImport   — non-null only where a FilamentProfile link makes sense;
 *              invoked AFTER the user confirms the preview dialog.
 */
@Composable
fun FilamentLibraryPicker(
    state: LibraryState,
    favourites: List<String>,
    recents: List<String>,
    onToggleFavourite: (String) -> Unit,
    onPick: (FilamentLibraryEntry) -> Unit,
    onImport: ((FilamentLibraryEntry) -> Unit)?,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) { /* ... */ }

@Composable
internal fun FilamentImportPreviewDialog(
    entry: FilamentLibraryEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) { /* ... */ }
```

Behaviour requirements (each backed by a structural assertion above):
1. `when (state)`: `Loading` → centered `CircularProgressIndicator`; `Failed` → message + "Retry" `TextButton` calling `onRetry`; `Ready` → content below.
2. Search `OutlinedTextField` (placeholder "Search brand, name, material…"), material `FilterChip` row: All / PLA / PETG / ABS / TPU / ASA (single-select; All = null filter).
3. Results from `library.search(query, materialFilter, favourites.toSet(), recents)` — recompute via `remember(query, materialFilter, favourites, recents, state)`.
4. On blank query, show section headers "FAVOURITES" / "RECENT" / "ALL" (small label style, matching the MIXES label in MainActivity:4398) before the respective groups; compute group sizes from the same favourites/recents inputs.
5. Row tap → `selected = entry`; selected row expands an action row: `Button("Use")` → `onPick(entry)`; plus `TextButton("Use + import profile…")` shown only when `onImport != null && hasImportableData(entry)` → sets `importTarget = entry`.
6. `importTarget?.let { FilamentImportPreviewDialog(it, onConfirm = { onImport!!(it); importTarget = null; onPick(it) }, onDismiss = { importTarget = null }) }` — preview lists `buildImportPreview(entry)` rows (label left, value right, note in `labelSmall` alpha 0.6 under the row), title "Import profile data", confirm button "Import".
7. Footer `Text` in `labelSmall`/alpha 0.5: `"OpenPrintTag database — ${'$'}{library.snapshot.count} filaments, snapshot ${'$'}{library.snapshot.date} (MIT)"` — uses `snapshot`.
8. Star: filled `Icons.Default.Star` when favourite, `Icons.Default.StarBorder` otherwise; `IconButton { onToggleFavourite(entry.slug) }`.
9. Bound the list height (`Modifier.heightIn(max = 380.dp)`) so host dialogs don't overflow.

- [ ] **Step 4: Run test + build to verify green**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.ui.FilamentLibraryPickerStructuralTest"` then `./gradlew assembleDebug --no-daemon`
Expected: PASS (7 tests), BUILD SUCCESSFUL.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/FilamentLibraryPicker.kt app/src/test/java/com/u1/slicer/ui/FilamentLibraryPickerStructuralTest.kt
git commit -m "feat(library): FilamentLibraryPicker composable + import preview dialog"
```

---### Task 7: Host the Library tab — `FilamentColorEditDialog` (AiPaint slots) + `ExtruderSlotEditDialog` (Printer slots)

Wires the picker into the two physical-slot contexts. CreateMixSlotDialog and the Prepare per-file colour dialog (MainActivity:4420) intentionally keep the plain HSV dialog — they are not physical-slot contexts (spec §4.4).

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/FilamentColorEditDialog.kt`
- Modify: `app/src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt` (dialog block ~496-517 + params ~30-40)
- Modify: `app/src/main/java/com/u1/slicer/navigation/NavGraph.kt` (~294-300)
- Modify: `app/src/main/java/com/u1/slicer/SlicerViewModel.kt` (next to `setSlotColor`, ~2077)
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` (`ExtruderSlotEditDialog` ~943, call site ~747, plus the chip-row parent that must pass library state down)
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` (library state + import)
- Test: `app/src/test/java/com/u1/slicer/ui/FilamentLibraryTabWiringTest.kt`

- [ ] **Step 1: Write the failing structural test** — `FilamentLibraryTabWiringTest.kt`:

```kotlin
package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Structural guards (spec §4.4): the Library tab exists ONLY in physical-slot
 * contexts. AiPaint slot dialog + PrinterScreen ExtruderSlotEditDialog host it;
 * CreateMixSlotDialog and the Prepare per-file dialog (MainActivity) stay HSV-only.
 */
class FilamentLibraryTabWiringTest {

    private fun src(p: String) = File(p).readText()

    @Test
    fun `colour dialog gains optional library tab`() {
        val dialog = src("src/main/java/com/u1/slicer/ui/FilamentColorEditDialog.kt")
        assertTrue(dialog.contains("libraryContent"))
        assertTrue(dialog.contains("TabRow") || dialog.contains("SegmentedButton"))
        assertTrue(dialog.contains("\"Library\""))
    }

    @Test
    fun `aipaint slot dialog passes library content`() {
        val s = src("src/main/java/com/u1/slicer/ui/AiPaintResultScreen.kt")
        assertTrue(s.contains("libraryContent"))
        assertTrue(s.contains("FilamentLibraryPicker"))
        assertTrue(s.contains("onPickLibraryFilament"))
    }

    @Test
    fun `mix and prepare dialogs stay hsv-only`() {
        assertFalse(src("src/main/java/com/u1/slicer/ui/CreateMixSlotDialog.kt").contains("libraryContent"))
        // MainActivity hosts the Prepare per-file colour dialog — must not opt in.
        val mainActivity = src("src/main/java/com/u1/slicer/MainActivity.kt")
        assertFalse(mainActivity.contains("libraryContent"))
    }

    @Test
    fun `printer slot edit dialog hosts the picker`() {
        val s = src("src/main/java/com/u1/slicer/ui/PrinterScreen.kt")
        assertTrue(s.contains("FilamentLibraryPicker"))
        assertTrue(s.contains("\"Library\""))
    }

    @Test
    fun `slicer viewmodel applies library pick to preset colour and material`() {
        val s = src("src/main/java/com/u1/slicer/SlicerViewModel.kt")
        assertTrue(s.contains("fun applyLibraryPick"))
        assertTrue(s.contains("recordRecent"))
    }

    @Test
    fun `printer viewmodel exposes library state and profile import`() {
        val s = src("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt")
        assertTrue(s.contains("filamentLibraryRepository") || s.contains("libraryRepo"))
        assertTrue(s.contains("fun importLibraryProfile"))
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.ui.FilamentLibraryTabWiringTest"`
Expected: FAIL on every assertion that names new symbols.

- [ ] **Step 3: Implement**

**(a) `FilamentColorEditDialog.kt`** — add trailing param `libraryContent: (@Composable () -> Unit)? = null`. When null: unchanged. When non-null: a `TabRow` (or `SecondaryTabRow`) with tabs "Custom colour" / "Library" above the existing content; tab 0 shows the current HSV column, tab 1 shows `libraryContent()`. Keep the existing Save/Cancel/Reset buttons on tab 0 only (the Library tab applies via its own Use button).

**(b) `SlicerViewModel.kt`** — alongside `setSlotColor` (line ~2077) add:

```kotlin
private val libraryRepo = container.filamentLibraryRepository  // adjust to the actual container access pattern used in this ViewModel

/** Library pick = colour + material onto the physical slot preset (F-library). */
fun applyLibraryPick(slotIndex: Int, entry: com.u1.slicer.data.FilamentLibraryEntry) {
    if (slotIndex !in 0..3) return
    viewModelScope.launch(Dispatchers.IO) {
        val cfg = printersRepo.config.first() ?: return@launch
        val active = cfg.active
        val existing = active.extruderPresets.firstOrNull { it.index == slotIndex }
            ?: com.u1.slicer.data.ExtruderPreset(index = slotIndex)
        val updated = existing.copy(
            color = entry.hex ?: existing.color,     // colour-less entry: material only
            materialType = entry.material.ifBlank { existing.materialType },
            filamentProfileId = null,                 // material changed → stale link cleared (mirrors applySyncResult)
        )
        val presets = (active.extruderPresets.filterNot { it.index == slotIndex } + updated).sortedBy { it.index }
        printersRepo.update(active.copy(extruderPresets = presets))
        libraryRepo.recordRecent(entry.slug)
    }
}

/** "Use + import profile…": upsert the FilamentProfile and link it to the slot. */
fun importLibraryProfileForSlot(slotIndex: Int, entry: com.u1.slicer.data.FilamentLibraryEntry) {
    viewModelScope.launch(Dispatchers.IO) {
        val dao = /* the same FilamentDao source this app already uses (container) */
        val existing = dao.getByName(entry.displayName)
        val profile = com.u1.slicer.data.libraryEntryToProfile(entry, existing)
        val id = if (existing != null) { dao.update(profile); existing.id } else dao.insert(profile)
        // re-apply pick with the link this time
        val cfg = printersRepo.config.first() ?: return@launch
        val active = cfg.active
        val preset = active.extruderPresets.firstOrNull { it.index == slotIndex }
            ?: com.u1.slicer.data.ExtruderPreset(index = slotIndex)
        val updated = preset.copy(
            color = entry.hex ?: preset.color,
            materialType = entry.material.ifBlank { preset.materialType },
            filamentProfileId = id,
        )
        val presets = (active.extruderPresets.filterNot { it.index == slotIndex } + updated).sortedBy { it.index }
        printersRepo.update(active.copy(extruderPresets = presets))
        libraryRepo.recordRecent(entry.slug)
    }
}

val libraryState = libraryRepo.state
val libraryFavourites = libraryRepo.favourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
val libraryRecents = libraryRepo.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
fun toggleLibraryFavourite(slug: String) { viewModelScope.launch { libraryRepo.toggleFavourite(slug) } }
fun retryLibraryLoad() { libraryRepo.retry(viewModelScope) }
init-block addition: libraryRepo.ensureLoaded(viewModelScope)
```

Notes for the implementer: SlicerViewModel's container access — check how it obtains `printersRepo` near the top of the class and mirror that for `filamentLibraryRepository` and the filament DAO (grep for how filament profiles reach SlicerViewModel/MainActivity today; use the same source, do not open a second DB handle).

**(c) `AiPaintResultScreen.kt`** — add params:
```kotlin
libraryState: com.u1.slicer.data.LibraryState = com.u1.slicer.data.LibraryState.Loading,
libraryFavourites: List<String> = emptyList(),
libraryRecents: List<String> = emptyList(),
onToggleLibraryFavourite: (String) -> Unit = {},
onPickLibraryFilament: (slot: Int, entry: com.u1.slicer.data.FilamentLibraryEntry) -> Unit = { _, _ -> },
onImportLibraryProfile: (slot: Int, entry: com.u1.slicer.data.FilamentLibraryEntry) -> Unit = { _, _ -> },
onRetryLibrary: () -> Unit = {},
```
In the dialog block (~506) pass:
```kotlin
libraryContent = {
    FilamentLibraryPicker(
        state = libraryState,
        favourites = libraryFavourites,
        recents = libraryRecents,
        onToggleFavourite = onToggleLibraryFavourite,
        onPick = { entry -> onPickLibraryFilament(slot, entry); editSlotColour = null },
        onImport = { entry -> onImportLibraryProfile(slot, entry); editSlotColour = null },
        onRetry = onRetryLibrary,
    )
},
```
**(d) `NavGraph.kt`** (~297) — wire the new params to `viewModel` (SlicerViewModel): `libraryState = viewModel.libraryState.collectAsState().value`, etc., `onPickLibraryFilament = { slot, entry -> viewModel.applyLibraryPick(slot, entry) }`, `onImportLibraryProfile = { slot, entry -> viewModel.importLibraryProfileForSlot(slot, entry) }`.

**(e) `PrinterViewModel.kt`** — add:
```kotlin
private val libraryRepo = (application as U1SlicerApplication).container.filamentLibraryRepository
val libraryState = libraryRepo.state
val libraryFavourites = libraryRepo.favourites.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
val libraryRecents = libraryRepo.recents.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
fun toggleLibraryFavourite(slug: String) { viewModelScope.launch { libraryRepo.toggleFavourite(slug) } }
fun retryLibraryLoad() { libraryRepo.retry(viewModelScope) }
fun recordLibraryRecent(slug: String) { viewModelScope.launch { libraryRepo.recordRecent(slug) } }

/** Upsert profile from a library entry; onDone delivers the row id on the main thread. */
fun importLibraryProfile(entry: com.u1.slicer.data.FilamentLibraryEntry, onDone: (Long) -> Unit) {
    viewModelScope.launch {
        val id = withContext(Dispatchers.IO) {
            val dao = /* same FilamentDao source used elsewhere in container */
            val existing = dao.getByName(entry.displayName)
            val profile = com.u1.slicer.data.libraryEntryToProfile(entry, existing)
            if (existing != null) { dao.update(profile); existing.id } else dao.insert(profile)
        }
        libraryRepo.recordRecent(entry.slug)
        onDone(id)
    }
}
```
Call `libraryRepo.ensureLoaded(viewModelScope)` in init.

**(f) `PrinterScreen.kt` `ExtruderSlotEditDialog`** — add params `libraryState/libraryFavourites/libraryRecents/onToggleLibraryFavourite/onRetryLibrary` plus `onImportProfile: (FilamentLibraryEntry, (Long) -> Unit) -> Unit`. Add a two-tab switch ("Colour" / "Library") at the top of the dialog `Column`. Library tab hosts `FilamentLibraryPicker` with:
```kotlin
onPick = { entry ->
    entry.hex?.let { color = it; val p = hexToHsv(it); hue = p[0]; sat = p[1]; hsv = p[2] }
    if (entry.material.isNotBlank()) materialType = entry.material
    linkedProfileId = null
    tab = 0  // bounce back so the user sees the applied colour and can Save
},
onImport = { entry ->
    entry.hex?.let { color = it; val p = hexToHsv(it); hue = p[0]; sat = p[1]; hsv = p[2] }
    if (entry.material.isNotBlank()) materialType = entry.material
    onImportProfile(entry) { id -> linkedProfileId = id }
    tab = 0
},
```
The dialog's existing Save button persists everything in one `onSave(preset.copy(color=..., materialType=..., filamentProfileId=linkedProfileId))` — verify that's how the current Save works and keep it. Thread the new params from the chip composable (line ~743) up to wherever PrinterScreen accesses `PrinterViewModel` (follow how `filaments` reaches it today).

- [ ] **Step 4: Run tests + build**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.ui.FilamentLibraryTabWiringTest"` then `./gradlew assembleDebug --no-daemon`
Expected: PASS (6 tests), BUILD SUCCESSFUL.

- [ ] **Step 5: Run the full unit suite (regression gate)**

Run: `./gradlew testDebugUnitTest --no-daemon`
Expected: all green. Investigate any failure — do not weaken assertions.

- [ ] **Step 6: Commit**

```bash
git add -A app/src/main app/src/test
git commit -m "feat(library): Library tab in AiPaint slot dialog + PrinterScreen slot editor"
```

---

### Task 8: `FilamentLibraryMatcher` (sync matching, pure)

**Files:**
- Create: `app/src/main/java/com/u1/slicer/data/FilamentLibraryMatcher.kt`
- Test: `app/src/test/java/com/u1/slicer/data/FilamentLibraryMatcherTest.kt`

- [ ] **Step 1: Write the failing tests** — `FilamentLibraryMatcherTest.kt`:

```kotlin
package com.u1.slicer.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilamentLibraryMatcherTest {

    private val lib = FilamentLibrary(
        entries = listOf(
            FilamentLibraryEntry("prusament-pla-galaxy-black", "Prusament", "PLA Prusa Galaxy Black", "PLA", hex = "#3E413F"),
            FilamentLibraryEntry("prusament-pla-azure-blue", "Prusament", "PLA Azure Blue", "PLA", hex = "#008FBE"),
            FilamentLibraryEntry("prusament-petg-jet-black", "Prusament", "PETG Jet Black", "PETG", hex = "#000000"),
            FilamentLibraryEntry("bambulab-pla-black", "Bambu Lab", "PLA Basic Black", "PLA", hex = "#000000"),
            FilamentLibraryEntry("acme-pla-nocolour", "Acme", "PLA Mystery", "PLA", hex = null),
        ),
        snapshot = LibrarySnapshotInfo("test", "2026-06-10", 5),
    )

    @Test
    fun `exact brand and colour match within threshold`() {
        val m = FilamentLibraryMatcher.match(lib, vendor = "Prusament", material = "PLA",
            subType = null, hex = "#3E413F")
        assertEquals("prusament-pla-galaxy-black", m!!.entry.slug)
        assertTrue(m.deltaE < 1.0)
    }

    @Test
    fun `vendor normalisation is case and punctuation insensitive both ways`() {
        val m = FilamentLibraryMatcher.match(lib, "bambu lab", "PLA", null, "#000000")
        assertEquals("bambulab-pla-black", m!!.entry.slug)
        val m2 = FilamentLibraryMatcher.match(lib, "BambuLab", "PLA", null, "#000000")
        assertEquals("bambulab-pla-black", m2!!.entry.slug)
    }

    @Test
    fun `colour beyond deltaE threshold rejects`() {
        // Azure blue reported as bright red — never a match even with right brand+material.
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "PLA", null, "#FF0000"))
    }

    @Test
    fun `material mismatch rejects even with perfect colour`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "ABS", null, "#3E413F"))
    }

    @Test
    fun `unknown vendor returns null - never guesses across brands`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Snapmaker", "PLA", null, "#000000"))
        assertNull(FilamentLibraryMatcher.match(lib, "NoSuchVendor", "PLA", null, "#3E413F"))
    }

    @Test
    fun `missing vendor or colour or material returns null`() {
        assertNull(FilamentLibraryMatcher.match(lib, null, "PLA", null, "#3E413F"))
        assertNull(FilamentLibraryMatcher.match(lib, "", "PLA", null, "#3E413F"))
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", "PLA", null, null))
        assertNull(FilamentLibraryMatcher.match(lib, "Prusament", null, null, "#3E413F"))
    }

    @Test
    fun `subtype tokens break colour ties toward the named filament`() {
        // Both Prusament PLAs compete on a colour between them; subtype "Galaxy" must win the tie.
        val between = "#1F6880"  // roughly between #3E413F and #008FBE in Lab — recompute if needed
        val withSub = FilamentLibraryMatcher.match(lib, "Prusament", "PLA", "Galaxy", between)
        if (withSub != null) {
            assertEquals("prusament-pla-galaxy-black", withSub.entry.slug)
        } else {
            // If the midpoint exceeds the gate for both, pick a closer probe to Galaxy Black.
            val m = FilamentLibraryMatcher.match(lib, "Prusament", "PLA", "Galaxy", "#37403E")
            assertEquals("prusament-pla-galaxy-black", m!!.entry.slug)
        }
    }

    @Test
    fun `entries without colour are never matched`() {
        assertNull(FilamentLibraryMatcher.match(lib, "Acme", "PLA", null, "#123456"))
    }

    @Test
    fun `threshold is pinned at 10`() {
        assertEquals(10.0, FilamentLibraryMatcher.MAX_DELTA_E, 1e-9)
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryMatcherTest"`
Expected: compilation FAILURE.

- [ ] **Step 3: Implement `FilamentLibraryMatcher.kt`**

```kotlin
package com.u1.slicer.data

import com.u1.slicer.aipaint.ColourMatch

/**
 * Identifies the exact catalogue filament from printer-reported RFID sync data
 * (vendor/material/subtype/colour). Conservative by design: returns a match only
 * when brand AND material agree and the colour is within a strict ΔE gate —
 * otherwise null and sync behaves exactly as before. Never guesses across brands.
 */
object FilamentLibraryMatcher {

    /** CIE76 gate — pinned by FilamentLibraryMatcherTest; tune only with new test evidence. */
    const val MAX_DELTA_E = 10.0

    /** Ranking bonus (in ΔE units) when subtype tokens appear in the entry name. */
    private const val SUBTYPE_BONUS = 3.0

    data class LibraryMatch(val entry: FilamentLibraryEntry, val deltaE: Double)

    fun match(
        library: FilamentLibrary,
        vendor: String?,
        material: String?,
        subType: String?,
        hex: String?,
    ): LibraryMatch? {
        if (vendor.isNullOrBlank() || hex.isNullOrBlank() || material.isNullOrBlank()) return null
        val vNorm = norm(vendor)
        if (vNorm.isEmpty()) return null

        val candidates = library.entries.filter { e ->
            if (e.hex == null) return@filter false
            if (!e.material.equals(material, ignoreCase = true)) return@filter false
            val bNorm = norm(e.brand)
            bNorm.isNotEmpty() && (bNorm.contains(vNorm) || vNorm.contains(bNorm))
        }
        if (candidates.isEmpty()) return null

        val ranked = candidates.map { e ->
            val dE = ColourMatch.deltaE76(hex, e.hex!!)
            val rank = dE - if (subtypeMatches(subType, e.name)) SUBTYPE_BONUS else 0.0
            Triple(e, dE, rank)
        }.sortedBy { it.third }

        val best = ranked.first()
        return if (best.second <= MAX_DELTA_E) LibraryMatch(best.first, best.second) else null
    }

    private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    private fun subtypeMatches(subType: String?, name: String): Boolean {
        if (subType.isNullOrBlank()) return false
        val nameLc = name.lowercase()
        return subType.lowercase().split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 3 }
            .any { nameLc.contains(it) }
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.data.FilamentLibraryMatcherTest"`
Expected: PASS (9 tests). If the subtype-tie test's probe colours both exceed the gate, adjust the PROBE COLOUR in the test (not the threshold, not the bonus) per the test's built-in fallback.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/data/FilamentLibraryMatcher.kt app/src/test/java/com/u1/slicer/data/FilamentLibraryMatcherTest.kt
git commit -m "feat(library): FilamentLibraryMatcher — conservative RFID sync matching, dE<=10 gate"
```

---

### Task 9: Sync integration — preview entries carry matches, dialog shows them, apply uses catalogue values

**Files:**
- Create: `app/src/main/java/com/u1/slicer/printer/SyncPreview.kt` (extracted pure builder)
- Modify: `app/src/main/java/com/u1/slicer/printer/PrinterViewModel.kt` (`SyncPreviewEntry` ~123, `syncFilaments` ~261, `applySyncResult` ~287)
- Modify: `app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt` (`FilamentSyncDialog`/`SyncEntryRow` ~1059+)
- Test: `app/src/test/java/com/u1/slicer/printer/SyncPreviewBuilderTest.kt`
- Test: `app/src/test/java/com/u1/slicer/ui/FilamentSyncMatchWiringTest.kt`

- [ ] **Step 1: Write the failing tests**

`SyncPreviewBuilderTest.kt`:

```kotlin
package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryEntry
import com.u1.slicer.data.LibrarySnapshotInfo
import com.u1.slicer.network.FilamentSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SyncPreviewBuilderTest {

    private val presets = (0..3).map { ExtruderPreset(index = it, color = "#111111", materialType = "PLA") }
    private val lib = FilamentLibrary(
        entries = listOf(
            FilamentLibraryEntry("prusament-pla-galaxy-black", "Prusament", "PLA Prusa Galaxy Black", "PLA", hex = "#3E413F"),
        ),
        snapshot = LibrarySnapshotInfo("test", "2026-06-10", 1),
    )

    private fun slot(i: Int, vendor: String, type: String = "PLA", hex: String = "#3E413F") =
        FilamentSlot(index = i, label = "E${i + 1}", color = hex, loaded = true,
            materialType = type, subType = "", manufacturer = vendor)

    @Test
    fun `matched slot carries catalogue name colour material`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), lib)
        val e = entries[0]
        assertEquals("Prusament PLA Prusa Galaxy Black", e.matchedName)
        assertEquals("prusament-pla-galaxy-black", e.matchedSlug)
        assertEquals("#3E413F", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `unmatched slot falls back to raw values exactly as before`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Snapmaker", hex = "#FF0000")), lib)
        val e = entries[0]
        assertNull(e.matchedName)
        assertNull(e.matchedSlug)
        assertEquals("#FF0000", e.newColor)
        assertEquals("PLA", e.newType)
    }

    @Test
    fun `null library means no matching - raw behaviour`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(0, "Prusament")), library = null)
        assertNull(entries[0].matchedName)
        assertEquals("#3E413F", entries[0].newColor)
    }

    @Test
    fun `missing printer slot yields null news`() {
        val entries = buildSyncPreviewEntries(presets, emptyList(), lib)
        assertEquals(4, entries.size)
        assertNull(entries[2].newColor)
        assertNull(entries[2].newType)
        assertNull(entries[2].matchedName)
    }

    @Test
    fun `four entries always built with current preset values`() {
        val entries = buildSyncPreviewEntries(presets, listOf(slot(1, "Prusament")), lib)
        assertEquals(listOf("E1", "E2", "E3", "E4"), entries.map { it.label })
        assertEquals("#111111", entries[0].currentColor)
        assertEquals("Prusament PLA Prusa Galaxy Black", entries[1].matchedName)
    }
}
```

`FilamentSyncMatchWiringTest.kt` (structural):

```kotlin
package com.u1.slicer.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sync dialog renders matched catalogue names; apply records library recents. */
class FilamentSyncMatchWiringTest {

    @Test
    fun `sync entry row shows matched name`() {
        val s = File("src/main/java/com/u1/slicer/ui/PrinterScreen.kt").readText()
        assertTrue(s.contains("matchedName"))
        assertTrue(s.contains("(matched)"))
    }

    @Test
    fun `syncFilaments builds entries through the pure builder with the library`() {
        val s = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
        assertTrue(s.contains("buildSyncPreviewEntries"))
    }

    @Test
    fun `apply records recents for matched applied slots`() {
        val s = File("src/main/java/com/u1/slicer/printer/PrinterViewModel.kt").readText()
        assertTrue(s.contains("recordRecent"))
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.printer.SyncPreviewBuilderTest" --tests "com.u1.slicer.ui.FilamentSyncMatchWiringTest"`
Expected: compilation FAILURE / assertion FAIL.

- [ ] **Step 3: Implement**

**(a) `SyncPreview.kt`** — pure builder (move-and-extend of the loop body currently inline in `syncFilaments`):

```kotlin
package com.u1.slicer.printer

import com.u1.slicer.data.ExtruderPreset
import com.u1.slicer.data.FilamentLibrary
import com.u1.slicer.data.FilamentLibraryMatcher
import com.u1.slicer.network.FilamentSlot

/**
 * Builds the 4-slot sync preview. When the library is loaded, each slot is run
 * through FilamentLibraryMatcher; a confident match replaces the raw RFID
 * colour/type with catalogue values and carries the catalogue name for the
 * dialog. No match (or library == null) → exact pre-library behaviour.
 */
fun buildSyncPreviewEntries(
    presets: List<ExtruderPreset>,
    slots: List<FilamentSlot>,
    library: FilamentLibrary?,
): List<PrinterViewModel.SyncPreviewEntry> = (0..3).map { i ->
    val preset = presets.getOrElse(i) { ExtruderPreset(i) }
    val printerSlot = slots.firstOrNull { it.index == i }
    val match = if (library != null && printerSlot != null) {
        FilamentLibraryMatcher.match(
            library,
            vendor = printerSlot.manufacturer,
            material = printerSlot.materialType,
            subType = printerSlot.subType,
            hex = printerSlot.color,
        )
    } else null
    PrinterViewModel.SyncPreviewEntry(
        slotIndex = i,
        label = "E${i + 1}",
        currentColor = preset.color,
        newColor = match?.entry?.hex ?: printerSlot?.color,
        currentType = preset.materialType,
        newType = match?.entry?.material ?: printerSlot?.materialType,
        matchedSlug = match?.entry?.slug,
        matchedName = match?.entry?.displayName,
    )
}
```

Note: the existing `syncFilaments` indexes slots positionally (`slots.getOrNull(i)`); `FilamentSlot.index` is 0-based and matches position today — `firstOrNull { it.index == i }` is equivalent and more explicit. If a regression test elsewhere disagrees, revert to positional.

**(b) `PrinterViewModel.kt`**:
- Extend `SyncPreviewEntry` with `val matchedSlug: String? = null, val matchedName: String? = null`.
- Replace the entry-building block in `syncFilaments()` (lines ~270-282) with:
```kotlin
val library = (libraryState.value as? com.u1.slicer.data.LibraryState.Ready)?.library
val entries = buildSyncPreviewEntries(extruderPresets.value, slots, library)
```
- In `applySyncResult`, after `printersRepo.update(...)`, record recents:
```kotlin
if (applyColors || applyTypes) {
    entries.mapNotNull { it.matchedSlug }.forEach { slug ->
        libraryRepo.recordRecent(slug)
    }
}
```
(`applySyncResult` already runs in `viewModelScope.launch`; `recordRecent` is suspend — call it inside that coroutine.)

**(c) `PrinterScreen.kt` `SyncEntryRow`** — when `entry.matchedName != null`, render under the existing colour/type row a line:
```kotlin
Text(
    "${entry.matchedName} (matched)",
    style = MaterialTheme.typography.labelSmall,
    color = MaterialTheme.colorScheme.primary,
)
```
The swatch already renders `entry.newColor`, which now IS the catalogue colour for matched rows — no further change. Apply-colours/apply-types toggles keep working unchanged.

- [ ] **Step 4: Run tests + full unit suite**

Run: `./gradlew testDebugUnitTest --no-daemon --tests "com.u1.slicer.printer.SyncPreviewBuilderTest" --tests "com.u1.slicer.ui.FilamentSyncMatchWiringTest"` then the full `./gradlew testDebugUnitTest --no-daemon`
Expected: new tests PASS (8), full suite green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/u1/slicer/printer app/src/main/java/com/u1/slicer/ui/PrinterScreen.kt app/src/test/java/com/u1/slicer/printer/SyncPreviewBuilderTest.kt app/src/test/java/com/u1/slicer/ui/FilamentSyncMatchWiringTest.kt
git commit -m "feat(library): sync preview matches catalogue filaments; dialog shows matched names"
```

---

### Task 10: Attribution + instrumented asset test

**Files:**
- Modify: `app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt` (About section, ~line 155-190)
- Test: `app/src/androidTest/java/com/u1/slicer/data/FilamentLibraryAssetTest.kt`

- [ ] **Step 1: Write the instrumented test** — `FilamentLibraryAssetTest.kt`:

```kotlin
package com.u1.slicer.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** The bundled filament library asset is packaged in the APK and parses at runtime. */
@RunWith(AndroidJUnit4::class)
class FilamentLibraryAssetTest {

    private fun load(): FilamentLibrary {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val text = ctx.assets.open(FilamentLibraryRepository.ASSET_NAME)
            .bufferedReader().use { it.readText() }
        return FilamentLibrary.parse(text)
    }

    @Test
    fun assetPackagedAndParses_withExpectedScale() {
        val lib = load()
        assertTrue("expected >10000 entries, got ${lib.entries.size}", lib.entries.size > 10000)
        assertEquals(lib.snapshot.count, lib.entries.size)
    }

    @Test
    fun knownEntryPresent() {
        val azure = load().entry("prusament-pla-azure-blue")!!
        assertEquals("Prusament", azure.brand)
        assertEquals("#008FBE", azure.hex)
    }
}
```

- [ ] **Step 2: Run it on-device**

Run: `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon "-Pandroid.testInstrumentationRunnerArguments.class=com.u1.slicer.data.FilamentLibraryAssetTest"`
Expected: 2 tests PASS (Pixel 8a must be connected).

- [ ] **Step 3: Add the attribution row to Settings ▸ About**

In `SettingsScreen.kt`, inside `SettingsSection("About")` after the GitHub row, add a clickable row matching the GitHub row's style:

```kotlin
// MIT attribution for the bundled OpenPrintTag filament library snapshot.
Row(
    modifier = Modifier
        .fillMaxWidth()
        .clickable {
            context.startActivity(
                Intent(Intent.ACTION_VIEW,
                    Uri.parse("https://github.com/OpenPrintTag/openprinttag-database"))
            )
        },
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically
) {
    Column {
        Text("Filament library: OpenPrintTag", style = MaterialTheme.typography.bodyMedium)
        Text(
            "MIT licence — bundled FFF snapshot",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
    Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
}
```

Build: `./gradlew assembleDebug --no-daemon` — BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/u1/slicer/ui/SettingsScreen.kt app/src/androidTest/java/com/u1/slicer/data/FilamentLibraryAssetTest.kt
git commit -m "feat(library): OpenPrintTag attribution in About + instrumented asset test"
```

---

### Task 11: Backlog + docs + GitHub issue

- [ ] **Step 1: Add the BACKLOG.md entry**

Find the next free feature number in `BACKLOG.md` (Fxx). Add under the open-features section, following the existing entry format:

```markdown
### F<N>: OpenPrintTag filament library + RFID sync matching (GitHub #<M>)
**Status:** IMPLEMENTED on `feature/filament-library` (pending Kevin's sanity test — not merged/released)
Bundled FFF-only snapshot of the MIT OpenPrintTag database (~13k filaments, 121 brands) as a
searchable Library tab in the slot colour dialogs (AiPaint slots + Printer slot editor):
search/material chips/favourites/recents, pick = colour + material, opt-in profile-data import
with preview (creates/updates a FilamentProfile, linked via filamentProfileId, deduped by name).
Sync matching: FilamentLibraryMatcher identifies the catalogue filament from RFID-derived
vendor/type/subtype/colour (ΔE76 ≤ 10 gate, never guesses); matched sync rows show the catalogue
name and apply catalogue values. TD/refractive index carried for future translucency work, NOT
used in slicing. Design: docs/superpowers/specs/2026-06-10-openprinttag-filament-library-design.md.
Conversion tooling: tools/openprinttag-convert (snapshot refresh is a release-flow step).
```

- [ ] **Step 2: Create the matching GitHub issue**

```bash
gh auth switch -u taylormadearmy
gh issue create --title "F<N>: OpenPrintTag filament library + RFID sync matching" --body "$(cat <<'EOF'
Bundle an FFF-only snapshot of the MIT-licensed OpenPrintTag database (~13k filaments) as a
searchable Library tab in the filament colour dialogs (pick = slot colour + material, opt-in
profile import with preview, favourites + recents), plus conservative sync matching that
identifies the exact catalogue filament from the printer's RFID-derived vendor/type/subtype/colour.

Design spec: docs/superpowers/specs/2026-06-10-openprinttag-filament-library-design.md
Branch: feature/filament-library
EOF
)"
```

Record the issue number, then update the BACKLOG heading's `(GitHub #<M>)` placeholder with it.

- [ ] **Step 3: Update test counts in CLAUDE.md and README.md**

After the full sweeps (Task 12) report final numbers: update the unit-test total + class count, the instrumented total + class count, and add one-line entries for each new test class to the CLAUDE.md test lists (follow the existing single-line format). Update README.md's total tests line. **Do this from actual sweep output, not by arithmetic.**

- [ ] **Step 4: Commit**

```bash
git add BACKLOG.md CLAUDE.md README.md
git commit -m "docs(library): BACKLOG F<N> entry + test counts for filament library"
```

---

### Task 12: Verification sweep + release APK staging

- [ ] **Step 1: Full unit suite** — `./gradlew testDebugUnitTest --no-daemon` → all green.
- [ ] **Step 2: Converter tests** — `python tools/openprinttag-convert/test_convert.py` → OK.
- [ ] **Step 3: Confidence check** — run the `u1-slicer-confidence-check` skill (unit + smoke-10 + E2E smoke-7, ~25 min, Pixel 8a `43211JEKB16931`). All three layers must pass.
- [ ] **Step 4: Manual on-device E2E of the new feature** (subagent-driven via adb; NEVER tap Map & Print / Send & Print):
  1. Open the app → Printer tab → tap an extruder slot chip → Library tab → search "prusament azure" → pick → verify slot colour turns azure blue and material PLA; reopen → favourites star a row → reopen → starred row listed first.
  2. Pick with "Use + import profile…" → confirm preview lists temps/density → verify Filaments tab shows "Prusament PLA Azure Blue" profile; import again → still exactly one profile.
  3. Smart Paint flow: open a model → Smart Paint → tap a slot colour → Library tab present; CreateMix dialog colour picker → NO Library tab.
  4. If the physical printer is reachable and idle: tap Sync on the Printer screen → verify the preview dialog renders (matched or raw rows both fine) → Cancel. Do not start any print.
- [ ] **Step 5: Full instrumented suite in the background** — `ANDROID_SERIAL=43211JEKB16931 ./gradlew connectedDebugAndroidTest --no-daemon` (expect >2 hrs; run in background). **No pre-existing failures exist — investigate every failure; never dismiss as flaky.**
- [ ] **Step 6: Release APK staged for Kevin** — after everything is green:
```bash
./gradlew assembleRelease --no-daemon
cp app/build/outputs/apk/release/app-release.apk "G:/My Drive/claude/u1-slicer-filament-library-$(git rev-parse --short HEAD).apk"
```
- [ ] **Step 7: Push the branch** (branch only — NO tag, NO release, NO merge):
```bash
gh auth switch -u taylormadearmy
git push -u origin feature/filament-library
```
- [ ] **Step 8: Stop.** Report to Kevin: feature summary, test results, APK location. Wait for his sanity test before any merge/release discussion.

---

## Self-review notes (already applied)

- Spec coverage: §4.1→Tasks 1-2, §4.2→Tasks 3-4, §4.3→Task 8, §4.4→Tasks 5-7, §4.5→Task 9, §2 attribution→Tasks 2+10, §6 edge cases→Tasks 3/5/6/8 tests, §7 test matrix→every task, §8 sequencing preserved (data+logic → picker UI → sync → sweep).
- "Physical slot contexts" resolved against the real code: AiPaint slot dialog (writes ExtruderPreset via `setSlotColor`) + PrinterScreen `ExtruderSlotEditDialog` (canonical preset editor). Prepare per-file rows and CreateMix keep HSV-only — pinned by `FilamentLibraryTabWiringTest`.
- Duplicate-import decision (spec §6 left open): dedupe by exact profile name `"<brand> <name>"` via new `FilamentDao.getByName` — no Room migration.
- Type consistency: `FilamentLibraryEntry`/`LibraryState`/`LibrarySnapshotInfo`/`buildSyncPreviewEntries`/`matchedSlug`/`matchedName` names used identically across Tasks 3-10.
