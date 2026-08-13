# SelfRun APK signing identity

> 이 문서의 기존 application ID/update lineage 규칙은 legacy SelfRun 0.2.x에 적용됩니다. SelfRun Drive는 `com.shaterguy.chatgptselfrun.drive`, `1.0.0-dev1`/`1000001`의 독립 계보이며 같은 인증서를 사용해도 legacy 앱의 업데이트가 아닙니다. Drive용 Android OAuth client에는 최종 Drive APK를 실제 서명한 이 인증서의 SHA-1을 별도로 등록해야 합니다.

From `v0.1.0-dev2` onward, sideloaded SelfRun APKs must preserve the following update identity.

- Application ID: `com.shaterguy.chatgptselfrun`
- First stable update baseline: `0.1.0-dev2` / versionCode `2`
- Signing certificate SHA-256: `b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe`
- Certificate validity: 2026-08-10 through 2056-08-10

Rules:

1. Never commit the signing passphrase, derived private key, PKCS#12 bundle, or any equivalent secret.
2. Derive the signing identity only from the private project signing passphrase with `tools/derive_signing_identity.py`.
3. Every installable APK at or after this baseline must use the certificate fingerprint above.
4. Keep `applicationId` unchanged and increase `versionCode` for each subsequent installable build.
5. Verify the APK with Android `apksigner` and record the certificate fingerprint before distribution.
6. `v0.1.0-dev1` used the CI debug signing identity and is not part of this update lineage. A device with dev1 installed may require one uninstall before installing dev2. After dev2 is installed, subsequent builds signed with this identity are intended to update in place.
