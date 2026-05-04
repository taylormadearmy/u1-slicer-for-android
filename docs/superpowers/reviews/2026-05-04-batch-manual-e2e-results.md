# Batch Manual E2E Results - 2026-05-04

Branch build tested: installed debug app `com.u1.slicer.orca` version `2.0.0` (`versionCode 260`) on Pixel 8a `43211JEKB16931`.

Scope: full AI-assisted batch manual E2E fixture set from `app/src/androidTest/assets`, covering STL, single/multi-colour 3MF, multi-plate selection, Shashibo plate 5, support/interface fixtures, and large fixtures.

Final result: **27 / 27 PASS**.

Validation applied for each scenario:
- Load file into the app-private debug flow.
- Select requested plate where applicable.
- Slice to `SliceComplete`.
- Export print-ready G-code through debug `EXPORT_GCODE`, which calls the same `prepareExportableGcode(...)` remap boundary used by Save/Share.
- Verify exported G-code has `export_T4_T9: 0`.

Important harness fixes made during this run:
- Multi-plate readiness: the runner now treats `isMultiPlate: true` as ready for plate selection instead of waiting forever for `ModelLoaded` before choosing a plate.
- Latest-state matching: state waits now inspect only the latest `DUMP_STATE` block, avoiding stale `ModelLoaded` lines from earlier in the same scenario.
- Export-event matching: export waits scan the full log for `EXPORT_GCODE: success`, because export success is a one-off log event rather than part of `DUMP_STATE`.
- Existing results can be preserved with `-KeepExistingResults`, which allowed interrupted/rerun scenarios to update the aggregate result set without deleting prior passes.

Final aggregate result files: `C:\tmp\e2e-results\??-*.txt`.

Final artifact directories used:
- `D:\projects\u1-slicer-orca\e2e-artifacts\batch-20260504-180700` for scenarios 1-5 initial pass.
- `D:\projects\u1-slicer-orca\e2e-artifacts\batch-20260504-183606` for resumed full pass over scenarios 6-27.
- `D:\projects\u1-slicer-orca\e2e-artifacts\batch-20260504-192321` and later focused rerun directories for scenarios that originally exposed harness timing/export wait bugs.

Final scenario status:

| # | Scenario | Result | Export T4-T9 |
|---:|---|---|---|
| 1 | 3DBenchy STL | PASS | 0 |
| 2 | tetrahedron STL | PASS | 0 |
| 3 | calib cube dual colour | PASS | 0 |
| 4 | colored 3DBenchy | PASS | 0 |
| 5 | H2C multi color Benchy | PASS | 0 |
| 6 | Dragon Scale infinity plate 3 | PASS | 0 |
| 7 | Dragon Scale 1 plate 2 colours | PASS | 0 |
| 8 | Dragon Scale new plate | PASS | 0 |
| 9 | flippy flappy mini plate 4 | PASS | 0 |
| 10 | flippy flappy mini painted | PASS | 0 |
| 11 | Shashibo plate 5 | PASS | 0 |
| 12 | Button for S trousers | PASS | 0 |
| 13 | Buzz multipart | PASS | 0 |
| 14 | Korok mask 4 colour | PASS | 0 |
| 15 | Flarewing dragon 4 filament | PASS | 0 |
| 16 | foldy coaster | PASS | 0 |
| 17 | slip slide spin fidget plate 3 | PASS | 0 |
| 18 | u1 auxiliary fan cover | PASS | 0 |
| 19 | old legacy 3mf | PASS | 0 |
| 20 | die single colour | PASS | 0 |
| 21 | Goat gray | PASS | 0 |
| 22 | Leo supports | PASS | 0 |
| 23 | Sensory twist ball | PASS | 0 |
| 24 | hanging pre cut colour | PASS | 0 |
| 25 | spiderman hanging pre cut | PASS | 0 |
| 26 | skywing seawing silkwing | PASS | 0 |
| 27 | F1 calendar | PASS | 0 |

Conclusion: the current branch passes the full batch manual E2E set after correcting the batch harness. The apparent Shashibo/large-fixture hangs were harness timing issues, not current-branch slicing failures in these final runs.
