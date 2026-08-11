# Bambu imported-profile fixtures

`dragon-scale-imported-profile.json` is a compact, hand-transcribed regression
fixture derived from `G:\My Drive\tes-data\Dragon Scale infinity.3mf`, read on
2026-08-05. It represents that file's `Metadata/project_settings.config` without
including the model, images, or the source machine macros.

The retained values are the real source profile's identity, representative
filament/AMS mapping, safe process and filament values, and target-owned P1S
fields.  Machine-G-code values are short sentinels, intentionally not copies of
the original macro bodies: tests must demonstrate that retargeting rejects them
and replaces them with the selected Bambu target's macros.
