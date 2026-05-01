# Review 4 — closeout pass on commit `02a8653`

Round 4 (closeout) review of the two fixes responding to Reviewer 3's
round-3 catches.

---

F2 NOT-ADDRESSED (Resolver wrong-slot expansion fallback) ADDRESSED:
resolveExportMapping() now implements the two-source priority, correctly
falling back to usedExtruderIndices.sorted() to safely derive canonical
mapping indices when legacy/recovery plate.filamentIndices are missing.

F7 NEW-CONCERN (Sparse compound-volume palette leak) ADDRESSED:
meshAlignedFilamentColors now sources from usedExtruderIndices rather
than the file-wide objectPartExtruders, structurally preventing
multi-plate files from leaking unrelated volume extruders into the
active plate's palette while still preserving compound-volume coverage.

---

Net result across rounds 1-4:

  Round 1 (full review):     5 P1 + 3 P2 + 3 architectural pivots flagged
  Round 2 (delta review):    5 partial findings → all 5 ADDRESSED in round 3
  Round 3 (delta review):    Reviewer 1 + 2 said all 6 ADDRESSED;
                              Reviewer 3 caught 2 (F2 not-addressed,
                              F7 new-concern)
  Round 4 (closeout):        Both ADDRESSED

Open items (deferred to fresh sessions per handoff spec):
  - B.2 config pipeline inversion
  - B.3 native-side PRINT_START heuristic replacement
  - PhysicalGcodePath constructor gating + full Save/Share/Jobs
    type-threading (Reviewer 1's bigger architectural ask)
  - Source-T-index defence-in-depth on Send (Reviewer 1's hardening)
