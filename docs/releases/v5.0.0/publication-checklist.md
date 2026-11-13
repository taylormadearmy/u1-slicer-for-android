# v5.0.0 publication checklist

Do not run the public-action section until the user explicitly authorizes publication. Replace `<candidate-commit>` with the reviewed release commit and `<approved-feature-branch>` with its branch.

## Prepare and verify the local candidate

1. Complete Phase 7.3. Refresh the final test counts and verify upgrade persistence.
2. Set `versionCode 500` and `versionName "5.0.0"` in `app/build.gradle`.
3. Run the exact unfiltered confidence command. Do not add skip, range, or class-filter arguments:

   ```powershell
   .\scripts\run-confidence-check.ps1
   ```

   This runs the configured smoke set and the wizard add-on. For this reconciliation, the user also requires every independent device test, all fixture-driven checks, and the full U1 and Bambu E2E batch. Record those results separately. Preserve the complete device sandbox before testing and verify its restoration afterward.
4. Build from a clean state to avoid the Play Protect packaging-cache issue:

   ```powershell
   .\gradlew.bat clean assembleRelease --no-daemon
   ```

5. Copy the exact release output to the branded filename, then record its checksum:

   ```powershell
   Copy-Item .\app\build\outputs\apk\release\app-release.apk .\u1-slicer-orca-v5.0.0.apk
   Get-FileHash .\u1-slicer-orca-v5.0.0.apk -Algorithm SHA256
   ```

6. Verify the branded APK signature and certificate before publication:

   ```powershell
   & 'D:\Android\Sdk\build-tools\34.0.0\apksigner.bat' verify --verbose --print-certs .\u1-slicer-orca-v5.0.0.apk
   ```

7. Update the local verification section in `docs/release-notes/v5.0.0.md` with the actual completed test results, upgrade and restoration evidence, and the checksum from step 5. Keep the tracked notes marked unpublished until the APK asset is visible on GitHub. Do not turn pending checks into passing claims.
8. Commit the version bump, refreshed counts, and finalized release-note evidence. Record the resulting SHA as `<candidate-commit>`.
9. Copy the tracked notes into a separate publication body without amending the candidate:

   ```powershell
   New-Item -ItemType Directory -Force .\release\phase7 | Out-Null
   Copy-Item .\docs\release-notes\v5.0.0.md .\release\phase7\v5.0.0-public-release-notes.md
   ```

   Edit that publication body to record `<candidate-commit>` and remove the local-candidate wording. Retain the completed verification results and compatibility limits. Verify that it has no unresolved placeholders.
10. Confirm that `git rev-parse HEAD` equals `<candidate-commit>`, the worktree is clean, and the recorded checksum and signer belong to `u1-slicer-orca-v5.0.0.apk`. Verify that the Orca submodule remote provides the recorded gitlink before pushing the parent branch. Keep public status fields at their previous published version until the v5.0.0 asset is visible on GitHub.

## Publish after explicit authorization

1. Authenticate with the repository owner account and verify it:

   ```powershell
   gh auth switch -u taylormadearmy
   gh auth status
   ```

2. Push the approved feature branch and verify the exact candidate commit:

   ```powershell
   git push origin <approved-feature-branch>
   git ls-remote origin refs/heads/<approved-feature-branch>
   ```

   Verify that the remote branch SHA equals `<candidate-commit>` before continuing.
3. Create the six follow-up issues before closing #152. Capture each returned URL and issue number:

   ```powershell
   gh issue create --title "Support delta and circular printer beds" --body-file .\docs\releases\v5.0.0\issues\01-delta-circular-beds.md
   gh issue create --title "Support shared-extruder multi-material machines" --body-file .\docs\releases\v5.0.0\issues\02-semm-mmu-machines.md
   gh issue create --title "Add SDCP and Duet printer transports" --body-file .\docs\releases\v5.0.0\issues\03-sdcp-duet-transports.md
   gh issue create --title "Converge Bambu machines on the machine-profile pipeline" --body-file .\docs\releases\v5.0.0\issues\04-bambu-convergence.md
   gh issue create --title "Support more than four physical extruders" --body-file .\docs\releases\v5.0.0\issues\05-more-than-four-extruders.md
   gh issue create --title "Generalize ColorMix and Smart Paint encoding" --body-file .\docs\releases\v5.0.0\issues\06-mix-encoding-generalization.md
   ```

4. Replace these exact tokens in `issue-152-closure.md` with the new issue numbers: `[FOLLOW-UP-DELTA-CIRCULAR-ID]`, `[FOLLOW-UP-SEMM-MMU-ID]`, `[FOLLOW-UP-SDCP-DUET-ID]`, `[FOLLOW-UP-BAMBU-CONVERGENCE-ID]`, `[FOLLOW-UP-MORE-THAN-FOUR-ID]`, and `[FOLLOW-UP-MIX-ENCODING-ID]`.
5. Create the tag at the exact candidate commit, push it, and create the release with the branded APK as its asset:

   ```powershell
   git tag v5.0.0 <candidate-commit>
   git push origin v5.0.0
   gh release create v5.0.0 .\u1-slicer-orca-v5.0.0.apk --target <candidate-commit> --title "v5.0.0" --notes-file .\release\phase7\v5.0.0-public-release-notes.md
   gh release view v5.0.0 --json url,tagName,targetCommitish,assets
   ```

6. Confirm that the release points to `<candidate-commit>` and lists `u1-slicer-orca-v5.0.0.apk`. Put the returned URL into `[V5.0.0-RELEASE-URL]` in `issue-152-closure.md`, then close #152:

   ```powershell
   $closureBody = Get-Content -Raw .\docs\releases\v5.0.0\issue-152-closure.md
   gh issue close 152 --comment $closureBody
   ```

7. In the same turn, change A2 from release candidate to released in `BACKLOG.md`, add the v5.0.0 release rollup, update current-release strings, and refresh final counts in `AGENTS.md`, `CLAUDE.md`, and `README.md`. Update the tracked release notes with the published release URL and candidate SHA. Mark the parent-owned Task 7.4 checkbox.
8. Commit and push the documentation sync separately:

   ```powershell
   git add BACKLOG.md AGENTS.md CLAUDE.md README.md docs/REFACTOR_STATUS.md docs/release-notes/v5.0.0.md docs/superpowers/plans/2026-09-15-multi-printer-phase7.md
   git commit -m "docs: sync v5.0.0 release status"
   git push origin <approved-feature-branch>
   ```
