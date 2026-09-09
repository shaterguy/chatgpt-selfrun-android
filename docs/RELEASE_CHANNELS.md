# SelfRun 3 release channels

이 저장소의 활성 제품 계보는 SelfRun 3 하나입니다. V1/V2 호환 런타임, WebView 0.2.x 유지보수 분기, 별도 legacy release channel을 현재 소스의 실행·배포 기준으로 사용하지 않습니다.

## 개발·후보·정식

- 개발 브랜치: `selfrun-v3/v<version>-devN`
- 릴리스 후보 브랜치: `selfrun-v3/v<version>-rcN`
- 정식 태그: `drive-v<version>`
- 정식 application ID: `com.shaterguy.chatgptselfrun.drive`
- TEST application ID: `com.shaterguy.chatgptselfrun.drive.test`

## 불변조건

- 신규 개발은 V3 ledger/adapter architecture에서만 수행합니다.
- V1/V2 executor, title-signal transport, rollover, legacy migration을 신규 V3 branch에 다시 도입하지 않습니다.
- 정식판과 TEST판은 package/UID/signing lineage를 분리해 동시 설치할 수 있어야 합니다.
- 개발 후보는 고정된 stable baseline과 직전 검증 candidate를 대상으로 update/co-install 검증을 수행합니다.
- 이미 발행된 태그나 Release는 저장소 이력으로 취급하며 이동·덮어쓰지 않습니다. 과거 태그가 현재 지원 계보라는 의미는 아닙니다.
- 정식 승격은 동일 source SHA에서 검증된 APK·서명·runtime evidence가 있을 때만 수행합니다.

## Candidate 검증

`.github/workflows/build-selfrun-v3-candidate.yml`이 V3 개발·RC 후보의 canonical 검증 경로입니다. static policy → 전체 JVM → APK build/sign → emulator runtime 순서로 진행하며, source input이 달라진 경우 새 run으로 검증합니다.
