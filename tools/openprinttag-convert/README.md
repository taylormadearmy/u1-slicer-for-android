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
