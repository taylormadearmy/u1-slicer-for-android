# AI Paint Result Screen — Multi-Level Segmentation UX

## Problem framing

The current screen presents a flat list of ~12 regions on top of 4 physical slots. With multi-level segmentation (Objects → Volumes → Topology components → Z-bands) one model can yield 1 row and another can yield 30+, and a single "region" no longer maps to one place in the model hierarchy. The user needs to: (a) understand what each row represents, (b) reassign any subtree to a slot, (c) recolour slots, and (d) overpaint with brush/lasso — without scrolling through dozens of leaf rows or losing track of which level they're operating on. We have one 3D viewer and finite vertical space on a phone.

---

## Pattern A — Expandable tree, single list

One LazyColumn. Top-level rows are Objects (or "the model" when there's only one). Each row has a chevron; expanding reveals Volumes, expanding a Volume reveals Components/Bands. All rows share the same shape (swatch · label · % · slot chips).

```
Goat-on-base.stl                  Benchy.3mf
+------------------------------+  +------------------------------+
| [3D viewer]                  |  | [3D viewer]                  |
+------------------------------+  +------------------------------+
| Paint  Lasso     [1][2][3][4]|  | Paint  Lasso     [1][2][3][4]|
+------------------------------+  +------------------------------+
| v [#] Goat (whole) 100% [mix]|  | v [#] Benchy   100%   [mix]  |
|   v [O] Body         63% [1] |  |   > [O] Hull         71% [1] |
|     . horns          4%  [3] |  |   > [O] Cabin        18% [2] |
|     . hooves L       2%  [4] |  |   > [O] Smokestack   6%  [3] |
|     . hooves R       2%  [4] |  |   > [O] Flag         3%  [4] |
|     . tail           3%  [2] |  |   > [O] Window glass 2%  [1] |
|   > [O] Base         37% [1] |  +------------------------------+
+------------------------------+
```

Legend: `[#]` model root · `[O]` object · `[V]` volume · `.` leaf · `[1..4]` current slot · `[mix]` mixed.

- **Paint mode:** the brush always paints triangles, independent of which tree row is selected. The little 4-chip row in the toolbar stays the source of "active slot." Selecting a parent row does NOT auto-select all its triangles; that's lasso's job.
- **Slot reassignment:** tap a slot chip on the row. Tapping it on a parent reassigns every leaf under it to that slot (with a small Undo snackbar). Long-press a parent → "Select all in 3D viewer" pushes its triangles into Lasso so you can prune before committing.
- **Mixed parent colour:** show the *dominant* slot's colour at full opacity plus a small diagonal stripe of the second-most-common slot — visually obvious, no extra label needed. If you want belt-and-suspenders, add a "mix" badge.
- **Pros:** one mental model, one list, scales 1→100 rows. Visual language already exists on this screen.
- **Cons:** deep nesting on goats with no objects (single root → single volume → 12 leaves) wastes two indent levels. Tap targets shrink at depth 3. Reassign-parent risks accidentally overwriting fine paint work — must show undo prominently.

---

## Pattern B — Two-tab list: "Parts" / "Bands"

A `TabRow` above the list. **Parts** = Objects/Volumes/Topology (anything spatial and named). **Bands** = the Z-band fallback, shown only when the model produced bands. On Benchy the Bands tab is empty/hidden; on a goat-pot it's the primary tab.

```
Goat-on-base.stl                  Benchy.3mf
+------------------------------+  +------------------------------+
| [3D viewer]                  |  | [3D viewer]                  |
+------------------------------+  +------------------------------+
| Paint Lasso      [1][2][3][4]|  | Paint Lasso      [1][2][3][4]|
+------------------------------+  +------------------------------+
| Parts (2)  |  Bands (12)     |  | Parts (5)  |  Bands -        |
+------------------------------+  +------------------------------+
| [O] Body          63% [1][.] |  | [O] Hull         71% [1][.]  |
| [O] Base          37% [1][.] |  | [O] Cabin        18% [2][.]  |
|                              |  | [O] Smokestack   6%  [3][.]  |
| (switch to Bands tab to see  |  | [O] Flag         3%  [4][.]  |
|  the 12 flood-fill regions)  |  | [O] Window glass 2%  [1][.]  |
+------------------------------+  +------------------------------+
```

- **Paint mode:** unchanged. Brush is the bridge — paint a band overrides whichever tab labelled it.
- **Slot reassignment:** same tap-chip pattern as today. No hierarchy, so no cascade to manage.
- **Mixed colour:** rare in this pattern because each row is a leaf in its own taxonomy. Only "Parts" parents (an Object that has been overpainted) need the mixed swatch.
- **Pros:** cognitive separation — "named parts" vs "regions of geometry" really are different concepts; tabs name that honestly. Each tab is a flat list, tap targets stay big. Easy to hide Bands entirely on Benchy.
- **Cons:** the user has to context-switch to find a region they saw on the model. Cross-tab operations (paint band 7 of Hull) feel awkward because the band and the part live in different tabs. Two scroll positions to remember.

---

## Pattern C — Object-first with drill-in sheet

The list always shows Objects (or one row if there are none). Tap a row → bottom sheet opens with the volumes/components/bands for that object, edited in isolation. The main list stays short and calm.

```
Goat-on-base.stl                  Benchy.3mf
+------------------------------+  +------------------------------+
| [3D viewer]                  |  | [3D viewer]                  |
+------------------------------+  +------------------------------+
| Paint Lasso      [1][2][3][4]|  | Paint Lasso      [1][2][3][4]|
+------------------------------+  +------------------------------+
| [#] Goat       100% [mix] >  |  | [O] Hull         71% [1] >   |
|     (1 object, 12 regions)   |  | [O] Cabin        18% [2] >   |
|                              |  | [O] Smokestack   6%  [3] >   |
| Tip: tap to break down.      |  | [O] Flag         3%  [4] >   |
|                              |  | [O] Window glass 2%  [1] >   |
+------------------------------+  +------------------------------+
        |
        v tap row
+------------------------------+
| Goat — 12 regions            |
| . horns           4%  [3][.] |
| . hooves L        2%  [4][.] |
| . tail            3%  [2][.] |
| . body shell     54%  [1][.] |
| ...                          |
| [Apply]   [Cancel]           |
+------------------------------+
```

- **Paint mode:** brush is global — works whether the sheet is open or closed. Sheet can stay open while painting if it doesn't cover the viewer.
- **Slot reassignment:** chip on the top-level row reassigns the whole object. Sheet has chips per child for fine control.
- **Mixed colour:** top-level row uses the same dominant-stripe trick as Pattern A. Sheet shows true per-leaf colours.
- **Pros:** the result screen stays at one screenful for every model. Drill-down is opt-in — users who don't want detail never see it. Maps cleanly onto Benchy's natural structure.
- **Cons:** modal sheet hides the 3D view (or steals half of it), so painting-while-drilling is awkward. Goat-style models (1 object, many bands) collapse to a single useless top-level row that you must always tap through. Two-level interaction adds one more state.

---

## Recommendation

**Ship Pattern A (expandable tree).** Reasoning:

1. **One model, one list, one place to look.** The user said "same list maybe different lists?" — Pattern A delivers "same list" with the structure visible inline. They can scroll a goat's 12 bands and a Benchy's 5 objects with identical muscle memory.
2. **It generalises.** If we later add a 5th level (e.g. AI semantic labels under Objects), it slots in as another indent without redesign.
3. **The current screen is already a list of rows with swatches and slot chips.** Pattern A is the smallest leap from today; Patterns B and C introduce tabs / sheets that we'd then have to test for edge cases (empty Bands tab, sheet over viewer, etc.).
4. **Cascade-reassign on parents is the killer feature** that makes Benchy fast: tap one chip, all of Hull flips to slot 2, done. Pattern A makes that the default gesture; B and C require opening a sheet or switching tabs.

Mitigations for Pattern A's weak spots:
- Auto-expand all rows when total leaf count ≤ 8 (so Benchy looks flat, goat shows its 12 bands without a tap).
- Auto-collapse to depth 1 when total leaf count > 20 (so a many-volume Bambu file isn't a wall of text).
- Show a "Reassigned 12 regions → Slot 2 · Undo" snackbar on every cascade so accidents are cheap.
- Tap target floor of 32dp at every depth; indent by 12dp (small) rather than 24dp so depth-3 still fits.

---

## Open questions

1. **Z-bands as fallback or supplement?** When an Object only flood-fills to one component, do we still expose Z-bands under it as a "more granular" affordance, or are bands strictly a last-resort substitute when nothing else segments?
2. **What's the canonical "level" the AI labels?** Does the AI name Objects ("Hull"), or does it name the slot-bucket regions ("Red"), or both? Affects whether labels live on tree parents or leaves.
3. **Does painting create a new tree node?** If I lasso 200 triangles in the middle of Hull and assign slot 3, does that become a child row under Hull, or is it invisible in the list and only visible on the model?
4. **Mixed-parent swatch — stripe vs badge?** Worth a quick visual test with both before locking in.
5. **Slot count when an object has > 4 leaves of distinct slots assigned:** still 4 physical slots, so the cascade is fine, but should the leaf-row chip row gray out the inactive slot icons when "you've already used all 4"?
