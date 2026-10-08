# SelfRun isolated upgrade fixture 1.0.2

Fixture-only correction, versionCode 3. App 4.0.10-dev3 and server 4.0.0-dev5 remain byte-for-byte unchanged. Signed candidate source e8a4e06f66025f9a7808dc1cc452c7470e06950e, successful CI37793257908 attempt1, artifact11557837430.

v1.0.1 has invalid pending-input seeding and lacks an Application initialization barrier; its phone failure and tablet pass cannot establish production data preservation. The old TEST rerun coordinator v1.0.0 is canceled and was not executed.

Changes: wait for callApplicationOnCreate to finish successfully, then waitForIdleSync; bind synthetic account/folder through Store API; start a synthetic run through Store.start, mark active paused projection, and save pending input through UserNextInputStore.save. No service, browser, user prompt, login or real user data is used. Capture snapshot once for SEED checkpoint and printed SHA. VERIFY emits synthetic field paths and before/after values before failing. Snapshot includes run projection. A deterministic negative control confirms normal cleanup removes all three fields from the former invalid seed before valid data is established.

The CI workflow pins the confirmed public installed TEST SHA under the same environment variable names. No repository variable, credential or permission is changed. Both TEST edges still require byte-exact match against their successful historical CI artifacts and existing TEST certificate. Four matrix jobs each receive a fresh emulator and use the exact signed candidate APK; production APKs are not rebuilt.

PHONE_TEST_INSTALLED_APK_SHA256=d2072f9db1ac342a3b7c35fdf7e9318e1569bfe901241da81cb1b4e1eb29ddab
TABLET_TEST_INSTALLED_APK_SHA256=e48a5ce9ceb8881617c1b0403e6c3972dbccb9fb90b303d4b2ad2eb2d2b3a1ca

Existing TEST certificate verified on both devices: 2c95a5644a0ef2959eaecf10460e300fe2ee7a4ebcede685a82a52634c22e86e. Device version/UID and install app-op are not inferred from failed queries. The new baseline APK match in CI must establish package/version lineage.

Local validation: unchanged production UserNextInputStore reproduces old invalid-seed loss (red), valid active-paused seed survives initialization (green); actual fixture class lifecycle/diff behavior5/5, metadata gate9/9, materialization gate5/5, SDK36 fixture build and shell syntax pass. Test framework boundary stubs are documented; no emulator update has been executed in cloud. New four-edge CI must pass before claiming preservation. See ROOT-CAUSE.md and DEVICE-APPLY-PLAN.ko.md.
