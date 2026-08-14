# SelfRun Drive

SelfRun Drive는 Google Docs 실행턴 문서의 단일행 signal event를 다음 턴 진행 기준으로 사용하는 Android 앱입니다. 저장소의 기본 브랜치 `main`은 SelfRun Drive 정식 계보를 가리킵니다. 기존 WebView SelfRun 0.2.x의 소스와 릴리스 이력은 `selfrun-webview/main` 및 기존 `v0.2.x` 태그에 분리 보존하며 Drive 코드와 병합하지 않습니다. 두 앱은 서로 다른 Android application ID를 사용하므로 동시에 설치할 수 있습니다.

| 항목 | WebView SelfRun | SelfRun Drive |
|---|---|---|
| application ID | `com.shaterguy.chatgptselfrun` | `com.shaterguy.chatgptselfrun.drive` |
| canonical branch | `selfrun-webview/main` | `main` |
| 정식 태그 | `v0.2.x` 등 | `drive-v1.x.x` |
| 완료 기준 | assistant WebView 상태 | Drive 작업문서 signal event |
| 버전 계보 | 0.2.x | 1.x |

세부 릴리스 분리 규칙은 [RELEASE_CHANNELS](docs/RELEASE_CHANNELS.md)를 따릅니다.

## Global SelfRun contract

SelfRun은 특정 ChatGPT Project에 종속되지 않습니다. Drive 앱은 모든 대화에서 같은 bootstrap을 만들고 다음 canonical 운영문서 ID를 전달합니다.

```text
SELF_RUN_SKILL_DOCUMENT_ID=1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs
```

앱은 이 문서를 직접 읽거나 파싱하지 않고 Project 이름·ID·SKILL 위치도 탐색하지 않습니다. 일반 Chat에서는 global SelfRun 운영문서와 사용자 요구로 실행하고, Project 내부 대화에서는 ChatGPT가 해당 Project 규범과 global SelfRun 규범을 함께 적용합니다.

Drive Runs의 canonical 안내 경로는 `/GPT/Self Run/Runs/`입니다. 이미 저장된 Runs folder ID가 접근 가능하면 경로 이동만으로 Picker 재연결을 요구하지 않습니다.

## 새 작업 UI

새 SelfRun Drive 작업의 실행 모드 기본값은 `일반 Chat · 모델 변경 없음`입니다. Work는 사용자가 명시적으로 선택할 때만 시작합니다.

셀프런 명령 입력창은 다중 행 내부 스크롤을 유지하면서, IME가 열린 상태에서 포커스·클릭·텍스트 변경이 발생하면 현재 커서 줄의 화면 표시 영역을 다시 요청합니다. Activity는 `SOFT_INPUT_ADJUST_RESIZE`를 유지하고 공통 UI 레이어가 IME inset을 반영합니다.

## Drive 실행 흐름

1. 앱이 Run과 Google Docs 실행턴 문서를 생성하고 정확한 `documentId`를 저장합니다.
2. bootstrap에는 Drive V1 식별자, global SelfRun Skill document ID와 turn document ID가 들어가며 사용자의 원본 요구사항이 뒤에 그대로 유지됩니다.
3. ChatGPT는 global SelfRun 운영문서를 읽고 명령 수신 전·턴 종료 전 Drive signal 계약을 수행합니다.
4. 앱은 마지막으로 소비한 실제 signal cursor 이후의 새 이벤트만 처리합니다. `modifiedTime` 자체나 assistant DOM 완료 상태는 진행 근거가 아닙니다.
5. TURN_COMPLETED 뒤 45초 UI guard를 거쳐 같은 conversation에 CONTINUE를 제출합니다. 새 ACK가 없으면 5분 뒤 같은 명령을 횟수 제한 없이 재시도합니다.
6. USER_ACTION_REQUIRED와 PAUSED는 보존형 일시정지이며 DONE만 정상 종료입니다.

외부 계약은 [SelfRun Drive V1 protocol](docs/SELF_RUN_DRIVE_V1_PROTOCOL.md), Android 내부 구현은 [SelfRun Drive runtime](docs/SELF_RUN_DRIVE_RUNTIME.md)에 있습니다.

## 빌드와 검증

요구 환경은 JDK 17, Android SDK 36, Build Tools 36.0.0, Gradle 9.5.0입니다.

```bash
tools/verify_drive_variant.sh
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

개발 CI는 `selfrun-drive/v*-dev*` 및 `selfrun-drive/v*-rc*` 브랜치를 대상으로 단위 테스트·Drive variant 정책·debug/release 빌드를 수행하고 aligned unsigned APK를 `chatgpt-selfrun-drive-unsigned` artifact로 보존합니다. 저장소에 `SELFRUN_SIGNING_PASSPHRASE` secret이 구성된 환경에서는 기존 SelfRun Drive 고정 인증서로 candidate APK까지 서명하고 packageName, versionName, versionCode, signing certificate와 SHA-256을 검증해 `chatgpt-selfrun-drive-build` artifact로 추가 제공합니다. secret이 없는 환경의 unsigned artifact는 최종 사용자 산출물이 아니며, 배포·사용자 전달 전에 `tools/sign_release.sh`와 기존 signing lineage를 사용해 별도 고정서명·검증해야 합니다.

## 정식 릴리스

검증된 Drive 개발·RC 최종 커밋을 저장소 기본 브랜치 `main`에 승격하면 `release-drive-v1.yml`이 다시 단위 테스트·동시 설치 격리·release 빌드·고정 서명을 검증합니다. 성공한 동일 커밋에 `drive-v<version>` lightweight tag를 만들고 APK와 SHA256SUMS를 GitHub Release에 첨부합니다.

`main`은 Drive 정식 계보의 기준 브랜치입니다. 기존 WebView SelfRun은 `selfrun-webview/main`에 보존하며 WebView 유지보수 또는 후속 릴리스가 필요한 경우 해당 계보에서만 진행하고 Drive `main`과 병합하지 않습니다. 기존 `v0.2.x` 태그와 GitHub Release는 변경하지 않습니다.

## Google Cloud 설정

Drive 앱용 Android OAuth client는 기존 WebView 앱과 별도로 아래 조합을 등록합니다.

```text
packageName=com.shaterguy.chatgptselfrun.drive
SHA-1=<배포 APK를 실제 서명한 인증서 SHA-1>
```

Drive API와 Google Docs API를 같은 Cloud project에서 활성화하고 승인 scope는 `https://www.googleapis.com/auth/drive.file`만 사용합니다. 앱은 client secret이나 OAuth client ID를 소스에 저장하지 않습니다.
