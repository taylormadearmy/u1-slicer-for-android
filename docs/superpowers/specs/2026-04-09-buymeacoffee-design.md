# Buy Me a Coffee — UX Design Spec

**Date:** 2026-04-09
**Status:** Approved

## Overview

Add two subtle but discoverable touch points linking to the developer's Buy Me a Coffee page (`https://buymeacoffee.com/taylormadearmy`). Both open the URL in the system browser via `LocalUriHandler`.

---

## Touch Point 1 — Header subtitle

**Location:** `TopAppBar` in `MainActivity.kt` (the main Scaffold, line ~738), used across all tabs.

**Change:** Replace the `coreVersion` `Text` composable (currently shows `"Snapmaker Orca 2.2.4 (Android ARM64)"`) with a tappable `"☕ Support development"` text.

**Behaviour:**
- Rendered as `labelSmall`, same colour as current (`MaterialTheme.colorScheme.primary`), at ~70% opacity so it doesn't compete with the "U1 Slicer" title
- Wrapped in a `clickable` modifier that calls `uriHandler.openUri(BMAC_URL)`
- No ripple beyond what `clickable` provides by default — keep it subtle
- The `coreVersion` StateFlow and its native library lookup can be removed entirely; it served no user-facing purpose

**Why:** The subtitle slot is currently wasted on engine version info users don't act on. Repurposing it keeps the header clean while making the link always-visible without being intrusive.

---

## Touch Point 2 — Settings "About" section

**Location:** `SettingsScreen.kt` — new section appended at the bottom of the existing settings list.

**Section heading:** `"About"` (styled consistently with other section headings in the file)

**Rows (in order):**
1. **Version** — left label `"Version"`, right value from `BuildConfig.VERSION_NAME` (e.g. `"v1.5.45"`)
2. **GitHub** — left label `"GitHub"`, right trailing `↗` icon; tapping opens `https://github.com/taylormadearmy/u1-slicer-orca`
3. **Buy Me a Coffee card** — full-width tappable card with BMaC yellow background (`#FFDD00`), coffee emoji (22sp), bold `"Buy Me a Coffee"` title in black, and `"buymeacoffee.com/taylormadearmy"` subtitle in dark grey

**Card behaviour:** Tapping anywhere on the card calls `uriHandler.openUri(BMAC_URL)`.

**Why:** The yellow card is immediately recognisable to anyone familiar with BMaC. Sitting below Version + GitHub it's in the expected "meta / about the app" location — discoverable without being pushy.

---

## Shared constant

Define once in a top-level constants file or at the top of `MainActivity.kt` / `SettingsScreen.kt`:

```kotlin
const val BMAC_URL = "https://buymeacoffee.com/taylormadearmy"
```

Both touch points reference this constant.

---

## GitHub row URL

```kotlin
const val GITHUB_URL = "https://github.com/taylormadearmy/u1-slicer-for-android"
```

---

## What is NOT changing

- No pop-ups, dialogs, or nag prompts
- No analytics or tracking on link taps
- No changes to any slicing, preview, or printer functionality
- The `coreVersion` StateFlow in `SlicerViewModel` is removed (dead code once the subtitle is replaced)

---

## Testing

No automated tests required — both touch points are pure UI with no logic to verify. Manual smoke test:
1. Tap subtitle in header → BMaC page opens in browser
2. Navigate to Settings → scroll to bottom → About section visible
3. Tap BMaC card → BMaC page opens in browser
4. Tap GitHub row → repo opens in browser
