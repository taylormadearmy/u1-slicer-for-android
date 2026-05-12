# F54 AI Paint — Design Spec

**Date:** 2026-05-12  
**Status:** Approved for implementation planning  
**Roadmap:** v1 ships Approach A (cloud); Approach C (on-device) follows later

---

## 1. Overview

Single-colour 3D models are common — downloaded STLs, simple prints, figurines — but the Snapmaker U1's 4-extruder capability is wasted on them. F54 adds an AI Paint feature that automatically segments a model into up to 4 colour regions and suggests realistic filament colours for each, turning any single-colour model into a multi-colour print with minimal user effort.

The feature also applies to already-painted multi-colour models, letting the user discard and redo the colour assignment via AI.

**Goal:** A user loads a dragon figurine (grey STL), taps "AI Paint", and gets a painted model with gold head, red body, blue wings, and dark base — ready to slice — in under 10 seconds.

---

## 2. User Flows

### 2a. Single-colour model (primary flow)

1. User loads a single-colour STL or 3MF onto the Prepare screen.
2. The Prepare screen shows a **colour count chip row** (fixed at 4 for v1) and an **✨ AI Paint** button beneath the model viewer.
3. User taps **AI Paint**. A progress indicator replaces the button while the pipeline runs (~3–8 seconds total).
4. On completion, the app navigates to the **AI Paint Result screen** (see §4).
5. User reviews the painted preview, optionally swaps colours, then taps **Use this painting**.
6. The app returns to Prepare with the model now treated as a painted multi-colour model. The existing Prepare → Send flow continues unchanged.

### 2b. Already-painted multi-colour model (recolour flow)

1. User has a painted multi-colour model on the Prepare screen (loaded from 3MF, or previously AI-painted).
2. A **✨ Recolour with AI** button is visible on the Prepare screen alongside the existing colour mapping controls.
3. Tapping it runs the same pipeline and navigates to the same AI Paint Result screen.
4. "Use this painting" replaces the previous colour assignment.

### 2c. Error / no API key

If no API key is configured, tapping AI Paint opens a bottom sheet explaining the feature requires a Claude or OpenAI API key, with a link to Settings. Settings has an "AI Paint API key" field (Claude API preferred; OpenAI as alternative).

---

## 3. Pipeline Architecture

The pipeline has 4 phases. Phases 1, 2, and 4 always run on-device. Phase 3 is a cloud call in v1 (Approach A) and will move on-device in a later version (Approach C) using Gemini Nano via ML Kit. The interface between Phase 2 and Phase 3 is the same regardless — making the swap additive, not a rewrite.

```
Phase 1 — Geometry pre-segmentation    [on-device, ~0.5–2s]
Phase 2 — Render region thumbnails     [on-device, ~0.3s]
Phase 3 — AI semantic labeling         [cloud in v1, ~2–5s]
Phase 4 — Write painted 3MF + preview  [on-device, ~0.5s]
```

### Phase 1 — Geometry pre-segmentation

Traverse the mesh already loaded in native memory (via `NativeLibrary.loadModel`). Cluster triangles into 4 candidate regions using:

- **Dihedral angle breaks**: triangles are in the same region if the angle between their face normals is below a threshold (~30°). Sharp edges become region boundaries.
- **Connected components**: flood-fill from seed triangles across the dihedral-angle graph.
- **Region merging**: if more than 4 regions result, iteratively merge the two smallest adjacent regions until exactly 4 remain.

Output: a `regionId: Int` (0–3) assigned to every triangle in the mesh. This assignment is computed in Kotlin using vertex position and normal data from `NativeLibrary.getPreparePreviewMesh()`, which returns a `NativePreviewMesh` with vertices in the 10-float-per-vertex format (3 pos + 3 normal + 4 RGBA). The `extruderIndices` ByteArray provides per-triangle indices; face normals are computed from the vertex positions. No ML required.

### Phase 2 — Render region thumbnails

Using the existing OpenGL ES renderer, render the model from 4 angles (front, back, left-isometric, right-isometric). Two render passes per angle:

1. **Shaded render** — normal lit render for the AI to see the model's shape.
2. **Region colour render** — each region painted a distinct solid colour: region 0 = red `#FF0000`, region 1 = green `#00FF00`, region 2 = cyan `#00FFFF`, region 3 = yellow `#FFFF00`. These four are maximally distinct and easily described in the prompt.

Output: 8 JPEG frames (~512×512 each), held in memory.

### Phase 3 — AI semantic labeling (cloud, v1)

Send a single API call to the configured vision LLM (Claude claude-sonnet-4-6 preferred; GPT-4o as fallback).

**Input:** the 8 rendered frames + a structured prompt:

> "These images show a 3D model rendered from 4 angles. The second image of each pair shows the model pre-segmented into 4 coloured regions: red=region0, green=region1, cyan=region2, yellow=region3. 
> 
> For each region: (1) identify what part of the model it represents (e.g. 'head', 'wings', 'armour', 'base'), (2) suggest a realistic hex filament colour that would look good when 3D printed.
>
> Respond as JSON: `{"regions": [{"id": 0, "label": "...", "colour": "#RRGGBB"}, ...]}`"

**Output:** parsed `List<AiRegion>(id, label, suggestedColour)`.

**Error handling:** if the API call fails or returns malformed JSON, fall back to 4 generic labels ("Region 1–4") with evenly distributed hue colours. The user can still manually assign colours.

**Approach C upgrade path:** In a later version, Phase 3 is replaced by a Gemini Nano (ML Kit GenAI) call using the same 8 frames and the same JSON output contract. The rest of the pipeline is unchanged.

### Phase 4 — Write painted 3MF + preview

Apply the `regionId → colour` mapping to produce a painted output:

- **Output format:** split the single mesh into 4 sub-meshes (one per region) and write a multi-object 3MF with each object assigned to extruder 1–4 via `Metadata/model_settings.config`. This avoids needing to generate the complex `paint_color` tree-encoding and reuses the existing multi-object pipeline exactly.
- Triangles on region boundaries have their shared vertices duplicated (standard mesh splitting — small seam at boundaries, acceptable for printed output).
- The resulting 3MF is saved to the app's cache dir and loaded as the active model, replacing the single-colour source.

Populate the `AiPaintResultState` and navigate to the Result screen.

---

## 4. UI Components

### AI Paint Result screen (`AiPaintResultScreen`)

- Full-screen view with the existing 3D model viewer at the top (~55% of screen height), showing the painted model. User can rotate/zoom.
- Region list below: 4 rows, each showing a colour swatch, the AI's semantic label ("Head & face"), and the region's approximate coverage percentage ("18% of model").
- Tapping a row opens the **Colour Swap sheet**.
- **Redo button** (bottom left): re-runs the full pipeline with a new random geometry seed. Replaces the current result.
- **"Use this painting" button** (bottom right, primary): commits the result and returns to Prepare.
- **Back arrow**: discards the result and returns to Prepare with the original model unchanged.

### Colour Swap sheet (bottom sheet)

- Shows the region label and AI-suggested colour at top.
- **Your loaded filaments** section: swatches for the 4 currently configured filaments. Tapping one assigns that filament's colour to this region. A checkmark shows the current assignment.
- **Colour picker** section: a simple hue + brightness strip for picking any colour (not bound to loaded filaments). Used when the user wants a colour not in their current filament set.
- **Apply** button commits.

### Prepare screen additions

- **Single-colour model**: beneath the colour count row (fixed "4" chip, non-interactive in v1), add an `✨ AI Paint` button (outlined secondary style, full width).
- **Multi-colour model**: add a small `✨ Recolour with AI` text button in the filament mapping area.
- Detection of "single-colour" = STL file (always single-colour by definition), or 3MF where `ThreeMfInfo.detectedExtruderCount <= 1` and `!hasPaintData`.

### Settings screen addition

- New "AI Paint" section with a single "API key" text field. Stored encrypted in DataStore. Accepts Claude or OpenAI keys (detected by prefix: `sk-ant-` = Claude, `sk-` = OpenAI).

---

## 5. Data Model

```kotlin
data class AiRegion(
    val id: Int,           // 0–3, matches regionId from Phase 1
    val label: String,     // e.g. "Head & face"
    val suggestedColour: String,  // hex "#RRGGBB"
    val userColour: String?,      // null = use suggestedColour
    val coverageFraction: Float   // 0.0–1.0
)

data class AiPaintResultState(
    val regions: List<AiRegion>,
    val paintedModelPath: String,  // path to the written 3MF in cache
    val sourceModelPath: String    // original, for Redo
)
```

`AiPaintViewModel` owns the pipeline execution and `AiPaintResultState`. It is separate from `SlicerViewModel` — the result is committed to `SlicerViewModel` only when the user taps "Use this painting".

---

## 6. Scope

### In v1
- Single-colour model → AI Paint → 4 regions → preview + adjust → slice
- Multi-colour model → Recolour with AI → same flow
- Colour swap per region (from loaded filaments or colour picker)
- Redo (re-run pipeline)
- API key in Settings (Claude primary, OpenAI fallback)
- Error state when no API key or call fails
- Single-plate models only

### Explicitly out of v1
- Manual repainting of region boundaries (full painting tool — a separate future feature)
- Merging or splitting AI-assigned regions
- Variable colour count (fixed at 4, matching U1 extruder count)
- On-device inference / Approach C (designed for, not implemented)
- Hosted backend / managed API key
- Multi-plate 3MF support

---

## 7. Future Work

### F54b — User segmentation hints
After the AI paints the model, the user can tap on a surface point in the 3D viewer and type a label ("eyes", "horns", "sword"). The app re-runs Phase 3 with the hint included in the prompt: *"Region 2 contains the character's eyes — please keep the eyes as a distinct region and label them accordingly."* This gives the user a way to correct missed semantic details without needing a full manual painting tool.

### F54c — Approach C (on-device inference)
Replace Phase 3 with Gemini Nano via ML Kit GenAI APIs (available on Pixel 6+ and other flagship devices). Same rendered frames, same JSON contract. Requires device capability check with cloud fallback for unsupported hardware. APK size impact: ~40MB for MobileSAM if the geometry pre-segmentation is upgraded to use it; Gemini Nano itself is downloaded separately via ML Kit, no APK impact.

### F54d — Fullspectrum / extended colour count
When Fullspectrum multi-material support lands (enabling more than 4 simultaneous filaments), the colour count picker becomes interactive (2–6+). The pipeline supports any N already; this is purely a UI unlock.
