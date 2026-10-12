# SelfRun Drive 릴리즈 채널 및 실행 경로

SelfRun Drive의 정식판과 TEST판은 서로 다른 applicationId·UID·서명 계보를 유지합니다. 이전 V1/V2 런타임은 신규 개발의 기준선이 아닙니다.

## 계보 및 불변조건

- 개발·RC 후보는 해당 버전의 `devN`·`rcN` 계보로 관리합니다. 정식 버전은 `drive-vX.Y.Z` 태그 및 정확한 GitHub source SHA와 연결합니다.
- 정식 applicationId는 `com.shaterguy.chatgptselfrun.drive`, TEST applicationId는 `com.shaterguy.chatgptselfrun.drive.test`입니다.
- 이전 정식 APK의 서명·패키지·versionCode 계보, 업데이트 시 UID·앱 데이터 보존 및 TEST 동시설치 조건은 유지합니다.
- 기존 태그·Release는 불변입니다. 재빌드로 기존 정식 자산을 덮어쓰거나 검증하지 않은 소스를 동일 태그로 이동하지 않습니다.

## 신규 정식 릴리즈의 단일 실행 진입점

`tools/selfrun_stable_release.sh`가 정식 버전의 **기본 서명 workflow를 자동 선택**합니다. 선택 대상은 `.github/workflows/sign-selfrun-drive-stable.yml`이며, GitHub-hosted Android emulator/instrumentation 전체 시험을 포함하지 않습니다. 이미 존재하는 구형 `release-drive-*.yml`이나 `build-selfrun-v4-dev.yml`을 정식 서명 기본 경로로 선택하지 않습니다.

1. 최종 릴리즈 후보 소스/버전 입력을 고정하고 해당 SHA를 GitHub 원격에 보존합니다. 로컬 Termux/ADB/실기기에서 가능한 정적·JVM·instrumentation·런타임 회귀를 검증하고, 같은 identity에 귀속된 기존 PASS는 재사용합니다. 테스트 결과가 없거나 환경상 검증할 수 없으면 정확한 조건을 `UNVERIFIED`로 남기며 GitHub emulator 실행으로 자동 우회하지 않습니다.
2. 기존 로컬 검증 원장을 아래 JSON 계약으로 기록합니다. `checks.runtime`에는 `PASS`·`REUSED`·`UNVERIFIED`를 기재하여 서명 이전 테스트의 실제 범위를 유지합니다. 이 파일은 이미 확보한 유효 증거를 가리킬 수 있으며, 단지 파일을 만들기 위해 테스트를 재실행할 필요는 없습니다. `checks.scope/static/unit`은 `PASS` 또는 `REUSED`여야 합니다.
3. 후보 Git 체크아웃에서 `bash tools/selfrun_stable_release.sh plan`으로 정확한 SHA·버전·선택 workflow를 먼저 확인한 뒤 `bash tools/selfrun_stable_release.sh dispatch --qa-report /path/to/local-qa.json`을 실행합니다. 해당 후보가 이 진입점 파일 추가 이전 커밋이라면 Termux에 설치된 `selfrun-stable-release` 명령을 후보 체크아웃에서 실행할 수 있습니다. 서명 workflow 자체는 GitHub `main`에 있으므로 후보 브랜치에 중복 복사할 필요가 없습니다. `dispatch`는 서명 비밀정보를 로컬로 가져오지 않고 GitHub에 보존된 후보 SHA를 pin하여 새 정식 서명 workflow만 실행합니다. 동일 source/version의 실행·이미 발행된 정식 Release가 있으면 중복 실행하지 않습니다.
4. 새 GitHub Actions 실행이 생성되면 해당 run의 `status`·`conclusion`을 GitHub에서 직접 terminal까지 확인합니다. 실행 중에는 상세 run/job/artifact/log를 조회하지 않으며 같은 담당 역할이 결과 확인을 책임집니다. terminal 성공 이후에만 `bash tools/selfrun_stable_release.sh collect --run-id <RUN_ID>`로 정식 서명 APK를 로컬에 회수하고, SHA-256·source commit·버전·package·인증서가 일치하는지 확인합니다.
5. 회수한 **동일한 정식 서명 APK**로 최신 정식 버전→신규 정식 버전 인플레이스 업데이트, 필요한 구버전 업데이트, UID·앱 데이터 보존, TEST 동시설치 및 최소 smoke test를 로컬에서 검증합니다. 기존 정확한 동일 서명 APK의 유효 증거는 재사용합니다. 필수 미검증 조건이 있으면 별도로 기록하고 결과를 부풀리지 않습니다.
6. 최종 확인된 APK 바이트를 그대로 `drive-vX.Y.Z` 태그와 GitHub Release에 게시합니다. 게시 전후 `source SHA → tag → signer → APK hash → Release asset`을 readback합니다. Release 파일명은 `chatgpt-selfrun-drive-vX.Y.Z.apk`를 기본으로 사용합니다. 원격 서명 아티팩트를 다시 빌드하거나 Github emulator를 다시 돌리지 않습니다.

로컬 검증 원장 예시(실제 성공·재사용 근거가 있는 경우에만 기록):

```json
{
  "schema": "selfrun-local-release-qa-v1",
  "source_sha": "<exact Git commit SHA>",
  "version_name": "4.0.11",
  "version_code": 4002022,
  "checks": {
    "scope": "PASS",
    "static": "PASS",
    "unit": "REUSED",
    "runtime": "UNVERIFIED"
  }
}
```

`runtime=UNVERIFIED`는 정식 런타임 검증 PASS가 아닙니다. 서명된 APK를 회수한 뒤 필수 업데이트 경로를 검증할 수 있도록 별도 단계로 남긴 것입니다.

예외적으로 현재 사용자가 GitHub 원격 Android 에뮬레이터 테스트를 명시하거나 GitHub 런타임 자체가 별도 완료조건으로 지정된 경우에만 해당 테스트를 **명시적으로 선택**합니다. 정식 서명 때문에 GitHub Actions가 필요하다는 이유만으로 에뮬레이터 전체 회귀시험을 자동 실행하지 않습니다.

## DEV·RC 후보 검증

DEV·RC 검증은 현재 해당 후보의 테스트 정책과 유효한 기존 증거에 따라 수행합니다. 과거 `.github/workflows/build-selfrun-v3-candidate.yml` 및 Android 에뮬레이터 검증 workflow가 저장소에 남아 있더라도 정식판 서명의 기본 실행 경로는 아닙니다. 새 정식 릴리즈 소스에는 위 실행 진입점이 포함되어 있어야 합니다.
