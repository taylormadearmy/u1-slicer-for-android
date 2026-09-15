# v5.0.0 publication checklist

Do not run this checklist until the user authorizes public release actions.

1. Complete Phase 7.3 and 7.4 validation, including upgrade persistence and final test-count refresh.
2. Authenticate GitHub CLI as `taylormadearmy`.
3. Push the approved branch.
4. Create six follow-up issues from `docs/releases/v5.0.0/issues/` and replace every `[FOLLOW-UP-ID]` placeholder in the #152 closure draft.
5. Create the new `v5.0.0` tag and GitHub release. Attach the branded `u1-slicer-orca-v5.0.0.apk` asset and verify that the asset appears on the release page.
6. Close #152 with `issue-152-closure.md`, replacing `[V5.0.0-RELEASE-URL]` and all follow-up placeholders.
7. In the same turn, change A2 from release candidate to released in `BACKLOG.md`, add the release rollup, verify release strings and final test counts in `CLAUDE.md`, `AGENTS.md`, and `README.md`, and mark the parent-owned Task 7.4 checkbox.
8. Commit the post-release documentation sync separately and push it.
