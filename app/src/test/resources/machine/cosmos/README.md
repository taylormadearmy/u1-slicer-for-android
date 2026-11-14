# Recorded COSMOS profile

`official-0.4.json` records the non-AFC 0.4 mm printer preset from the
[official COSMOS bundle](https://cloud.orcaslicer.com/b/3fad3c38f25f), linked by
[OpenCentauri's installation guide](https://docs.opencentauri.cc/klipper-conversion/cosmos/install/#required-orcaslicer-profile).
Retrieved 2026-10-23; profile version `2.4.0.2`.

The preset inherits `Elegoo Centauri Carbon 0.4 nozzle`. The app uses its existing
resolved ECC geometry, replaces the firmware templates with this recorded delta,
and disables shared-extruder multi-material mode for its single physical nozzle.
The template test decodes the cloud export's escaped newlines before comparing.
No network access is required to load the app preset or run the tests.

COSMOS 26.07+ deliberately emergency-stops on stock `M729`/`M8213`. This preset
uses `PRINT_START EXTRUDER=… BED=… CHAMBER=…`, `PRINT_END`, and `PAUSE` instead.
It does not cover AFC/CANVAS or establish physical hardware compatibility.
