# Generalize ColorMix and Smart Paint encoding

## Current limit

ColorMix and Smart Paint encode virtual mix slots relative to a four-slot physical tool space. The v5.0.0 candidate therefore enables mix features only for four-slot printers.

## Acceptance criteria

- Replace the fixed `4 + k` virtual-slot encoding with a representation derived from the active physical slot count.
- Migrate persisted mix recipes and session state without changing their component assignments.
- Update native paint, preview, G-code, and upload mapping to use the generalized representation.
- Add round-trip and slicing tests for one-, two-, four-, and more-than-four-slot profiles.
