# SelfRun Drive 1.0.0

SelfRun Drive는 Google Docs 실행턴 문서의 단일행 signal event를 다음 턴 진행 기준으로 사용하는 별도 Android 앱입니다. 기존 WebView SelfRun 0.2.x를 업데이트하거나 대체하지 않으며 두 앱은 동시에 설치할 수 있습니다.

| 항목 | WebView SelfRun | SelfRun Drive |
|---|---|---|
| application ID | `com.shaterguy.chatgptselfrun` | `com.shaterguy.chatgptselfrun.drive` |
| canonical branch | `main` | `selfrun-drive/main` |
| 정식 태그 | `v0.2.x` 등 | `drive-v1.0.0` 등 |
| 완료 기준 | assistant WebView 상태 | Drive 작업문서 signal event |
| 버전 계보 | 0.2.x | 1.x |

세부 릴리스 분리 규칙은 [RELEASE_CHANNELS](docs/RELEASE_CHANNELS.md)를 따릅니다.

## 새 작업 UI

새 SelfRun Drive 작업의 실행 모드 기본값은 `일반 Chat · 모델 변경 없음`입니다. Work는 사용자가 명시적으로 선택할 때만 시작합니다.

셀프런 명령 입력창은 다중 행 내부 스크롤을 유지하면서, IME가 열린 상태에서 포커스·클릭·텍스트 변경이 발생하면 현재 커서 줄의 화면 표시 영역을 다시 요청합니다. Activity는 `SOFT_INPUT_ADJUST_RESIZE`를 유지하고 공통 UI 레이어가 IME inset을 반영합니다.

## Drive 실행 흐름

1. 앱이 Run과 Google Docs 실행턴 문서를 생성하고 정확한 `documentId`를 저장합니다.
2. bootstrap과 이후 CONTINUE를 같은 ChatGPT conversation의 composer에 제출합니다.
3. ChatGPT는 명령을 실제 수신하면 작업 전 `SELF_RUN_COMMAND_RECEIVED` 한 줄을 작업문서에 기록하고 readback합니다.
4. 턴을 계속할 때는 최종 답변 직전 `SELF_RUN_TURN_COMPLETED`를 기록하고 readback합니다.
5. 앱은 마지막으로 소비한 실제 signal cursor 이후의 새 이벤트만 처리합니다. modifiedTime 자체나 assistant DOM 완료 상태는 진행 근거가 아닙니다.
6. TURN_COMPLETED 뒤 45초 UI guard를 거쳐 같은 conversation에 CONTINUE를 제출합니다. 새 ACK가 없으면 5분 뒤 같은 명령을 횟수 제한 없이 재시도합니다.
7. USER_ACTION_REQUIRED와 PAUSED는 보존형 일시정지이며 DONE만 정상 종료입니다.

상세 규격은 [SelfRun Drive V1 protocol](docs/SELF_RUN_DRIVE_V1_PROTOCOL.md)에 있습니다.

## 빌드와 검증

요구 환경은 JDK 17, Android SDK 36, Build Tools 36.0.0, Gradle 9.5.0입니다.

```bash
tools/verify_drive_variant.sh
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

개발 CI는 `selfrun-drive/v*-dev*` 브랜치만 대상으로 하며 Gradle의 현재 Drive versionName/versionCode를 동적으로 읽어 APK package/version/label을 검증합니다. GitHub-hosted runner의 임시 debug 서명 APK는 사용자 업데이트 자산으로 배포하지 않습니다. `SELFRUN_SIGNING_PASSPHRASE` secret이 있으면 고정 SelfRun 인증서로 candidate APK를 서명하고 인증서 fingerprint를 검증합니다.

## 정식 릴리스

검증된 Drive 최종 커밋을 `selfrun-drive/main`에 승격하면 전용 `release-drive-v1.yml`이 다시 단위 테스트·동시 설치 격리·release 빌드·고정 서명을 검증합니다. 성공한 동일 커밋에 `drive-v<version>` lightweight tag를 만들고 다음 자산으로 GitHub Release를 생성합니다.

```text
chatgpt-selfrun-drive-v1.0.0.apk
SHA256SUMS.txt
```

Drive Release는 `--latest=false`로 생성하여 WebView 계보의 저장소 Latest 상태를 임의로 바꾸지 않습니다. 기존 WebView `release-v0.2.2.yml`, `v0.2.x` 태그와 `main`의 WebView 정식 계보는 수정하지 않습니다.

## Google Cloud 설정

Drive 앱용 Android OAuth client는 기존 WebView 앱과 별도로 아래 조합을 등록합니다.

```text
packageName=com.shaterguy.chatgptselfrun.drive
SHA-1=<배포 APK를 실제 서명한 인증서 SHA-1>
```

Drive API와 Google Docs API를 같은 Cloud project에서 활성화하고 승인 scope는 `https://www.googleapis.com/auth/drive.file`만 사용합니다. 앱은 client secret이나 OAuth client ID를 소스에 저장하지 않습니다.
