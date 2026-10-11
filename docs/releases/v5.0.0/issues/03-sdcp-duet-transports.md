# Add SDCP and Duet printer transports

## Current limit

The v5.0.0 generic path uploads through Moonraker, OctoPrint, or PrusaLink. Printers that require SDCP or Duet APIs cannot connect or upload.

## Acceptance criteria

- Add explicit SDCP and Duet transport implementations with capability declarations.
- Validate endpoints and credentials before saving a printer.
- Support status polling and upload-only behavior for each transport.
- Add contract tests for authentication, upload success, upload failure, and the absence of an implicit print-start request.
