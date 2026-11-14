# Configure another printer

New Centauri Carbon and generic Klipper support is beta. Protocol tests run against mock servers, not physical printers. Native slicing and UI tests require an ARM64 Android device. Do not treat a successful connection test as proof that a machine profile is correct.

## Centauri Carbon 1

1. Open Settings, then add a printer.
2. Select the Elegoo CC1 connection and enter its IP address or hostname, without a port or path.
3. Test the connection, then save.
4. Slice a single-filament model. The app uses the bundled Centauri Carbon 0.4 mm machine profile.
5. Choose Upload Only to transfer the file without starting it.

CC1 uses UDP port 3000 for identification, WebSocket port 3030 for SDCP status and commands, and HTTP port 80 for uploads. The app rejects Carbon 2 identification. Start, pause, resume, and cancel use documented SDCP commands. Camera, heater, light, and arbitrary G-code controls are not exposed for CC1.

## Generic Klipper through Moonraker

1. Add a printer with the Moonraker connection.
2. Select Generic Klipper as the machine.
3. Import an Orca machine JSON profile. Import a flattened profile, or one JSON array containing its parent profiles and exactly one selectable leaf.
4. Enter the Moonraker URL, test the connection, then save.
5. Slice an unpainted, single-filament model and use Upload Only for the first transfer.

Profiles must declare Klipper firmware, one nozzle, a rectangular bed starting at 0,0, a positive maximum height, and start and end G-code templates. Missing parents and unsupported layouts produce an import error. Macros in imported templates must exist on the actual printer. The app cannot verify their definitions offline.

The selected profile owns geometry, firmware commands, and machine limits. Imported model projects contribute only allowed process and filament settings. U1 light, remote-screen, four-slot mapping, and ColorMix behavior do not apply to generic machines.

## OctoPrint

Select the OctoPrint connection, enter the host URL and API key, and import the same supported single-nozzle Klipper machine profile. URLs may include a reverse-proxy path prefix. This initial implementation does not accept Marlin machine profiles.

OctoPrint provides status, upload, explicit start, pause, resume, and cancel. Upload uses `select=false` and `print=false`. Starting a print is a separate action. Camera and heater controls are not exposed by this adapter.

## Saved jobs and switching printers

New jobs retain their machine target and imported-profile digest. A generic job cannot be sent to another profile or to a U1 without reslicing. The app checks the selected printer again before starting an uploaded job. An ambiguous acknowledgement is an error, not a reason to automatically retry a print-start command.

Pre-existing jobs retain legacy U1 interpretation because they were saved without a target. Saved Bambu jobs without a packaged print project need reslicing before sending.

## Protocol references

The protocol adapters are based on the [official Elegoo Link SDK](https://github.com/elegooofficial/elegoo-link) and [OctoPrint REST documentation](https://docs.octoprint.org/en/main/api/). The CC1 machine profile comes from the checked-in OrcaSlicer Elegoo profile inheritance chain. SliceBeam's unreleased development client supplied a feasibility reference, not an Android dependency.
