# Support delta and circular printer beds

## Current limit

The v5.0.0 generic machine path accepts rectangular FDM beds. Catalogue filtering rejects delta and circular geometry because placement, bounds validation, arrange, and preview overlays assume an X/Y rectangle.

## Acceptance criteria

- Parse circular and delta bed geometry from resolved Orca profiles without reducing it to an unsafe rectangle.
- Use the real bed boundary for placement, arrange, wipe-tower checks, preview, and G-code validation.
- Add JVM geometry tests and native slicing tests for at least one pinned catalogue profile of each supported geometry.
- Reject a model outside the real printable boundary even when it remains inside the bounding rectangle.
