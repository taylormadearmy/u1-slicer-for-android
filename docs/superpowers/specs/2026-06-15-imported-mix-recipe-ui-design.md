# Imported Mix Recipe UI

**Date**: 2026-06-15  
**Status**: Draft

## Problem

Some imported 3MF files carry their own `mixed_filament_definitions` recipe in `Metadata/project_settings.config`. The slicer now honors that recipe for slicing, which is correct, but the UI still presents only the current project mix list. That makes it hard to tell:

1. whether the file imported its own recipe,
2. which recipe will actually be sliced, and
3. how to edit the imported recipe without silently changing file provenance.

The goal is to make imported recipes visible and usable without reintroducing the old ambiguity.

## Goals

1. Show when an imported 3MF has an embedded mix recipe.
2. Make the file recipe the default visible source for imported models.
3. Keep the imported recipe read-only by default.
4. Provide an explicit action to create an editable project copy.
5. Preserve the existing slice-time file-first precedence.

## Non-Goals

1. No modal prompt on every import.
2. No automatic conversion of every imported recipe into the mutable project mix list.
3. No change to the slicer's recipe precedence rule.
4. No attempt to fully redesign the filament screen.

## Proposed UX

### Imported Recipe Card

When a loaded model has a non-empty embedded `mixed_filament_definitions` string, show a compact card at the top of the Filaments section.

The card should contain:

1. A title such as `Imported recipe active`.
2. A short provenance line such as `Using the recipe embedded in this 3MF for slicing`.
3. A small summary such as `6 mix rows` or `1 imported mix row`.
4. Two actions:
   - `View recipe`
   - `Create editable copy`

### View Recipe

`View recipe` opens a read-only bottom sheet or inline expansion showing the imported rows.

The display should be clearly marked as imported and non-editable. It should show enough to be useful:

1. Component labels such as `E1 + E2 + E4`
2. Weights
3. Distribution mode
4. Top-surface settings when present

If a row cannot be parsed cleanly, the UI should fall back to a compact raw-row display rather than hiding the recipe.

### Create Editable Copy

`Create editable copy` duplicates the imported recipe into the project mix manager and switches the working slice source to that copy.

After the copy is created:

1. The project copy becomes editable in the normal mix editor.
2. The imported recipe card remains visible as provenance.
3. The UI should label the editable copy as the current working set.
4. The user can explicitly revert to the imported recipe later without losing the imported baseline.

This keeps file provenance intact while still giving the user a normal edit flow when they want to diverge from the file.

## State Model

Add an explicit imported-recipe state so the app can distinguish:

1. file-embedded recipe
2. editable project copy
3. ordinary project mixes with no embedded recipe

The simplest shape is:

1. `embeddedMixRecipe: String?`
2. `embeddedMixSummary: ParsedMixRecipeSummary?`
3. `mixRecipeSource: FILE | PROJECT`

The slice path should continue to use the embedded string when `mixRecipeSource == FILE`, and the project manager serialization when `mixRecipeSource == PROJECT`.

## Implementation Outline

### Parsing

Add a small Kotlin parser for the native recipe string format used by `mixed_filament_definitions`.

The parser only needs to support the subset we display:

1. Split rows on `;`
2. Read `a`, `b`, `mix_b_pct`
3. Read `g...` component ids
4. Read `w...` component weights
5. Read `m...`, `t...`, `f...`, `i...`

The parser may ignore unsupported tokens and degrade gracefully.

### ViewModel

When a 3MF is loaded and `_sourceConfig["mixed_filament_definitions"]` is non-empty:

1. Capture the embedded recipe string.
2. Parse it into a summary for the UI.
3. Default `mixRecipeSource` to `FILE`.
4. Leave the project mix manager untouched unless the user chooses `Create editable copy`.

When `Create editable copy` is tapped:

1. Reconstruct editable rows from the imported recipe.
2. Replace the project mix manager's project rows with those rows.
3. Switch `mixRecipeSource` to `PROJECT`.

### UI

In the Filaments section:

1. Show the imported card above the normal project mix list.
2. Show the project mix editor only when the user has created a copy or the file has no embedded recipe.
3. Keep the imported card visible even after copying, so provenance stays obvious.

## Error Handling

1. If the imported recipe string exists but parsing fails, slicing should still work because the raw string is already authoritative.
2. The UI should display a compact warning and a raw fallback instead of blocking the user.
3. If the recipe is absent, behavior should remain unchanged and the normal project mix editor stays primary.

## Tests

Add regression coverage for:

1. File-embedded recipe is displayed as the active source when present.
2. `Create editable copy` switches the active source to the project mix manager.
3. Missing embedded recipe falls back to the current project mix list.
4. Parsing a canonical recipe string produces the expected row summary.
5. Slice-time precedence still uses the embedded recipe string for imported 3MFs.

## Revert Behavior

If the user edits the working copy, show a `Revert to imported recipe` action that restores the file recipe as the active slice source and discards the working copy.
