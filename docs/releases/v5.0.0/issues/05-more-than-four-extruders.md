# Support more than four physical extruders

## Current limit

The v5.0.0 generic path accepts 1 to 4 physical slots. UI state, palette handling, mapping, and several native assumptions still cap the physical tool space at four.

## Acceptance criteria

- Derive the physical tool limit from the active profile across persistence, UI, slicing, preview, validation, and upload.
- Preserve multi-digit tool indices in G-code and historical jobs.
- Add unit and native tests for at least one profile with more than four physical extruders.
- Keep four-slot U1 and Bambu behavior unchanged.
