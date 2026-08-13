# SelfRun APK signing identity

> 이 문서의 기존 application ID/update lineage 규칙은 legacy SelfRun 0.2.x에 적용됩니다. SelfRun Drive는 `com.shaterguy.chatgptselfrun.drive`의 독립 계보입니다. Drive `1.0.0-dev1` CI artifact는 Actions signing secret 부재 시 ephemeral debug signing identity로 배포될 수 있었으므로 고정 update lineage 기준선으로 사용하지 않습니다. `1.0.0-dev2` / `1000002`부터 아래 고정 인증서를 Drive installable APK에도 적용합니다. Drive용 Android OAuth client에는 최종 Drive APK를 실제 서명한 이 인증서의 SHA-1을 별도로 등록해야 합니다.

From `v0.1.0-dev2` onward, sideloaded legacy SelfRun APKs must preserve the following update identity.

- Application ID: `com.shaterguy.chatgptselfrun`
- First stable update baseline: `0.1.0-dev2` / versionCode `2`
- Signing certificate SHA-256: `b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe`
- Certificate validity: 2026-08-10 through 2056-08-10

SelfRun Drive uses the same certificate but an independent package/update lineage.

- Application ID: `com.shaterguy.chatgptselfrun.drive`
- First stable Drive update baseline: `1.0.0-dev2` / versionCode `1000002`
- Signing certificate SHA-256: `b3ea944ac1e31438ad697482af6d289c5ffeb0119e89c2e54a755c49c48644fe`
- `1.0.0-dev1` CI debug APK is not part of the stable Drive update lineage.

Rules:

1. Never commit the signing passphrase, derived private key, PKCS#12 bundle, or any equivalent secret.
2. Derive the signing identity only from the private project signing passphrase with `tools/derive_signing_identity.py`.
3. Every installable APK at or after each stable baseline must use the certificate fingerprint above.
4. Keep the corresponding `applicationId` unchanged and increase `versionCode` for each subsequent installable build.
5. Verify the APK with Android `apksigner` and record the certificate fingerprint before distribution.
6. Gradle debug APKs produced on ephemeral CI runners must not be distributed as updateable install artifacts.
7. A device with legacy `v0.1.0-dev1` or Drive `1.0.0-dev1` ephemeral debug APK installed may require one uninstall before installing the applicable stable-baseline APK. After that baseline is installed, subsequent builds signed with this identity are intended to update in place.
