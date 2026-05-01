reviewer 1

F8 (GcodeRenderer palette cap): ADDRESSED — GcodeRenderer.setExtruderColors() now replaces a dynamic palette array sized to max(hexColors.size, 4) instead of mutating a fixed 4-slot array, which structurally removes high-T color collapse rather than patching a single fixture.

F2 (resolver wrong-slot expansion): ADDRESSED — resolveCanonicalExportMapping() now supports confirmedMappingFileIndices sparse-keyed expansion and SlicerViewModel.resolveExportMapping() supplies plate-derived canonical file indices for plate-narrowed mappings, closing the class where positional expansion misrouted non-zero canonical indices.

F3 (job share single-color slot): ADDRESSED — schema v6 persists selectedExtruderAtSlice, startSlicing writes it, and shareJobGcode() uses it when canonicalListSize == 1, so single-color historical shares no longer default to slot 0 and this is robust across DB-restored jobs.

F7 (sparse compound-volume palette): ADDRESSED — meshAlignedFilamentColors now unions per-object and per-volume extruder sources (objectExtruderMap + objectPartExtruders) before compaction, which structurally fixes compound-object sparse palettes instead of only covering one collapsed-map case.

F4 (cache identity write coverage): ADDRESSED — both async refresh (refreshCanonicalFilamentList) and lazy canonical-load path in selectPlate now write canonicalCacheSourcePath in lock-step with _canonicalFilamentList, closing the identity-tag drift class rather than just one callsite.

Hardening (resolver clamp): ADDRESSED — resolver now clamps all mapping-derived slot values (confirmedMapping and selected extruder) to 0..3, which is a structural guard against malformed/stale mapping data producing out-of-range printer-bound T indices.

reviewer 2

F8 GcodeRenderer palette cap ADDRESSED: The renderer now dynamically sizes its palette array to accommodate all canonical indices (maxOf(hexColors.size, 4)), structurally eliminating the index-clamping bug for high-T slice outputs.

F2 Resolver wrong-slot expansion ADDRESSED: resolveExportMapping now extracts exact canonical indices and builds a sparse map, ensuring plate filaments are mapped by their true canonical IDs rather than relying on flawed positional logic.

F3 Job share single-color slot ADDRESSED: Schema v6 successfully persists selectedExtruderAtSlice, ensuring shareJobGcode routes single-color historical jobs to the user's actual chosen physical slot instead of defaulting to T0.

F7 Sparse compound-volume palette ADDRESSED: meshAlignedFilamentColors now unifies per-object and per-volume (objectPartExtruders) mappings, structurally guaranteeing that all compound-volume filament assignments are correctly colored in the preview.

F4 Cache identity write coverage ADDRESSED: The canonicalCacheSourcePath is now strictly synchronized during refreshCanonicalFilamentList and selectPlate, fully closing the stale cache identity leak during asynchronous loads.

Hardening — resolver clamp ADDRESSED: resolveCanonicalExportMapping now actively clamps all mapped outputs to the valid physical slot range (0..3), providing a structural backstop against malformed database state leaking invalid T-commands to the hardware.


reviewer 3

F8 GcodeRenderer palette cap: ADDRESSED - extruderColors now grows to canonical palette length and GcodeSegmentPacker.pack() receives the widened array, so T4+ no longer clamps to slot 3.

F2 Resolver wrong-slot expansion: NOT-ADDRESSED [P1] - the helper supports keyed sparse mappings, but resolveExportMapping() only supplies keys from plate.filamentIndices, so older/empty-filament-index plate metadata still falls back to positional expansion.

F3 Job share single-color slot: ADDRESSED - schema v6 persists selectedExtruderAtSlice, migration exists, slicing writes it, and shareJobGcode() uses it for canonicalListSize == 1.

F7 Sparse compound-volume palette: NEW-CONCERN [P2] - the palette now unions objectPartExtruders, but native-first buildThreeMfInfoFromNative() does not narrow that deprecated map to the selected plate, so multi-plate files can leak other plates’ volume extruders into the compact preview order.

F4 Cache identity write coverage: ADDRESSED - async refresh and select-plate lazy canonical writes now update canonicalCacheSourcePath, and null-file refresh clears it.

Hardening resolver clamp: ADDRESSED - confirmed mappings and single-color selected slots are clamped through the helper, including keyed sparse mappings.

Focused check passed: .\gradlew.bat :app:testDebugUnitTest --tests com.u1.slicer.gcode.CanonicalExportMappingTest.