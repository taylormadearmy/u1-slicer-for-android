# Release checks

Use `scripts/release.py` for every new public APK. It does not replace functional regression testing or authorize publication. Run `python scripts/test-release.py` when changing the gate.

## Prepare a committed candidate

Commit the version bump and source fixes, push the source commit, and complete the required functional checks. Then run:

```powershell
python scripts/release.py prepare --version 5.0.14
```

The gate rejects tracked source changes, clean-builds Release, checks the existing signing certificate, app ID/version, non-debuggable manifest and native packaging, and writes the branded APK and `candidate.json` under `.audit/releases/5.0.14/`. A used evidence directory cannot be overwritten. Do not rebuild or modify that APK after checking installation.

## Check the actual installer

Preserve the approved test phone's original app and complete app data before any replacement or downgrade; use the existing backup and byte-verified restore procedure. Never uninstall or clear the user's app as a shortcut. Do not change global protection settings or start a physical print.

On an online approved test phone, confirm Play Protect is enabled. Transfer the prepared APK and verify its hash, open it from Files or a browser download, and complete installation through the system installer. Confirm the installed version, run a Play Protect scan, and record the outcome with screenshots or a recording. ADB install success, an instrumentation pass, or selecting 'Install anyway' does not count. If there is a warning, preserve its full text and the exact APK, stop publication and investigate or appeal. Restore the original app/data when finished and verify restoration.

Only after observing a completed installation and scan without warnings, record the check:

```powershell
python scripts/release.py record-install --version 5.0.14 --device <approved-phone> --method files --apk-sha256 <candidate-sha256> --play-protect-enabled --evidence <screenshot-or-recording-path>
```

This command records an operator attestation, not an automated malware verdict. The gate checks its APK hash, method, protection state and evidence-file hash. The operator must record observations honestly; a screenshot alone cannot prove that the system scanned the file. Preserve evidence locally because it can contain device information.

## Publish and verify

With explicit user authorization and GitHub authenticated as `taylormadearmy`:

```powershell
python scripts/release.py publish --version 5.0.14 --notes <release-notes-file>
```

The source commit must still match, and the APK and evidence must be unchanged. The gate refuses an existing tag, creates a lightweight tag at the recorded source commit, pushes and verifies it, creates a draft release with the explicit target, and downloads the attached branded APK to compare its SHA-256. Only then does it publish. Complete the post-release backlog/docs/issue sync in CLAUDE.md. If a step fails after tag creation, retain the evidence and draft and investigate; do not retarget a public tag or blindly rerun with the same version.

## Investigation: v5.0.12 warning (2026-11-26)

The user observed a harmful-app warning for v5.0.12 and no warning for v5.0.13. The latter only changed versionCode/versionName and was clean-built. Both APKs use the same signing certificate, permissions and native library. Their manifest differs only in version. Their code/optimization entries and overall APK hashes differ. The original v5.0.11 APK also produced a warning during restoration. These observations favor an artifact-specific or changing classifier decision, but do not identify its cause. Rebuilding is not a reliable remedy, and changing signing keys would break update continuity.

The wrong v5.0.12 source tag was a separate confirmed release mistake. Google documents APK static/dynamic analysis, on-device machine learning, similarity checks, signatures and changing cloud information. We found no documented mechanism comparing a sideloaded APK with its GitHub source tag. Distribution information may be one signal, so an indirect relationship cannot be ruled out; correcting the tag is not evidence of a malware-warning fix. Because version, APK bytes and time all changed together, this was not a controlled experiment.

A manual installer gate catches observed warnings before publication; it cannot guarantee acceptance on every phone or prevent later reclassification. Retain blocked APKs and use Google's appeal process when a classification appears incorrect. No APK has been submitted to a third-party scanner by this workflow.

Sources checked 2026-11-26:

- [Google: on-device protections](https://developers.google.com/android/play-protect/client-protections)
- [Google: cloud-based protections](https://developers.google.com/android/play-protect/cloud-based-protections)
- [Google: warning developer guidance and appeals](https://developers.google.com/android/play-protect/warning-dev-guidance)
