# Isolated upgrade fixture 1.0.1

Independent instrumentation module; app 4.0.10-dev3 and server 4.0.0-dev5 sources are unchanged.

Build with `build-fixture.sh`. Outputs are unsigned and are not installation deliverables. In authorized CI, sign each fixture variant using the existing corresponding formal/TEST signing mechanism, without generating or replacing keys. Never export signing secrets.

Run only on a fresh dedicated emulator, one per edge: phone-formal (4.0.8 / 4002010), tablet-formal (4.0.0 / 4001000), phone-test (4002010), tablet-test (4000002), upgrading to 4.0.10-dev3 / 4002016. Physical serials and non-emulator hardware are refused; existing target installations are preserved and rejected. There is no uninstall or data clear.

`run-isolated-upgrade.sh` requires nine arguments shown in its usage plus ADB_SERIAL, ANDROID_HOME, SOURCE_COMMIT_FILE, CI_ARTIFACT_ID and CANDIDATE_SHA256SUMS. Obtain SOURCE_COMMIT.txt, APK and signed SHA manifest from the same exact CI artifact. The read-only GitHub gate checks exact commit, successful completed run, run attempt and unexpired artifact belonging to that run. An artifact uploaded with always() from a failed run is rejected. Formal baseline SHA and all signing certificates are pinned. TEST baseline SHA must first be independently verified against the actual prior release; neither TEST edge has a verified baseline APK identity yet.

SEED creates one paused synthetic task and event through the app engine/ledger, synthetic account-folder binding through the existing store API, runtime settings and an unsubmitted pending input. VERIFY opens existing stores after `adb install -r`, compares the canonical complete selected snapshot and package UID, and emits only digest/status. No prompt submission, browser operation, login or token collection occurs.

Validation so far: module compiled/packaged with SDK 36; shell syntax passes; CI run/artifact gate 9/9 tests pass; reflection contracts checked on all three baseline source trees. No signed emulator execution has occurred. These checks do not establish real device account authentication, actual user data preservation, or prior TEST-to-candidate update success. Those remain predeployment gates, along with successful exact candidate CI run.

See DEVICE-APPLY-PLAN.ko.md and fixture-ci-workflow.yml for pinned source/run and the four-edge execution plan. Version 1.0.1 adds CI materialization only; production app/server bytes remain unchanged. Cloud artifact download currently returns HTTP 403; CI must materialize its own exact artifact. Existing TEST artifact association is not a substitute for actual installed APK identity.
