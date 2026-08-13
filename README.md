# SelfRun Drive 1.0.0-dev2

SelfRun Drive는 Google Docs 네이티브 실행턴 문서를 다음 턴 진행의 기준 원본으로 사용하는 별도 Android 앱입니다. 기존 SelfRun 0.2.x를 업데이트하거나 데이터를 이전하지 않습니다.

| 항목 | 기존 SelfRun 0.2.x | SelfRun Drive |
|---|---|---|
| application ID | `com.shaterguy.chatgptselfrun` | `com.shaterguy.chatgptselfrun.drive` |
| 앱 이름 | SelfRun | SelfRun Drive |
| 완료 기준 | assistant WebView DOM | 저장된 `documentId`의 Drive commit |
| 데이터·쿠키 | 기존 앱 전용 | 신규 앱 전용 |
| 버전 | 0.2.x 계보 | `1.0.0-dev2` / `1000002` |

두 application ID가 다르며 shared UID, provider authority, 앱 간 마이그레이션이나 쿠키 공유를 사용하지 않습니다. 같은 서명 인증서를 사용해도 Android에서 별도 앱으로 설치됩니다.

## 실행 흐름

1. 사용자가 Google Picker로 기존 `/GPT/Project/Vibe Coding/00_System/SelfRun/Runs/` 폴더를 한 번 연결합니다.
2. 앱은 `drive.file`만 승인받고 Drive `about.user.permissionId`와 선택한 폴더 ID를 저장합니다.
3. 새 Job마다 Job 폴더 ID를 먼저 발급·영속하고 선택한 폴더 ID를 명시적 parent로 폴더를 만든 뒤, 그 ID를 명시적 parent로 Google Docs 문서를 만듭니다. 네이티브 문서 생성 응답이 불명확하면 검색·재생성하지 않고 보존형 중단합니다.
4. 초기 블록을 한 번 쓰고 metadata와 본문을 readback한 뒤에만 ChatGPT 새 대화를 만들고, 실행별 Drive metadata와 사용자의 작업 지시로 구성된 간결한 bootstrap을 제출합니다. 문서 기록 규격은 공식 운영문서가 담당하며 bootstrap에 반복 삽입하지 않습니다.
5. 이후 앱은 저장한 `documentId`의 `version`과 `modifiedTime`만 polling하며, 변경 시에만 본문을 읽습니다.
6. 유효한 `CONTINUE` commit을 영속 저장하고 120초 guard 후 같은 conversation의 입력창에 기존과 동일한 `[SELF_RUN_CONTINUE <RUN_ID>]` 한 줄을 제출합니다. Drive commit ID는 앱 내부 중복 방지에만 사용합니다.
7. `DONE`, `PAUSE`, `USER_ACTION_REQUIRED`는 continuation을 제출하지 않습니다.

Drive 대기와 guard에서는 WakeLock 및 assistant DOM 평가를 실행하지 않습니다. WebView는 최초 bootstrap과 정확한 conversation의 입력창을 통한 continuation 제출에만 사용됩니다.

새 conversation URL이 사용자 턴 DOM보다 먼저 확정되는 정상 전환에서는 URL을 영속 저장하고 최대 120초 동안 정확한 bootstrap 사용자 턴을 기다립니다. 이때 최초 prompt를 재전송하거나 assistant DOM을 검사하지 않습니다.

상세 문서 규격은 [SelfRun Drive V1 protocol](docs/SELF_RUN_DRIVE_V1_PROTOCOL.md)에 있습니다.

## Google Cloud 설정

Google Cloud Console에서 기존 OAuth client를 변경하지 말고 아래 조합의 Android OAuth client를 새로 등록해야 합니다.

```text
packageName=com.shaterguy.chatgptselfrun.drive
SHA-1=<배포 APK를 실제 서명한 인증서 SHA-1>
```

Drive API와 Google Docs API를 같은 Cloud project에서 활성화합니다. 앱은 client secret이나 OAuth client ID를 소스에 넣지 않으며 Google Identity Services의 Android package/signing identity를 사용합니다.

승인 scope는 다음 하나뿐입니다.

```text
https://www.googleapis.com/auth/drive.file
```

Picker 결과는 `AuthorizationResult.getTokenResponseParams()`의 `picked_file_ids`에서 정확히 한 개의 폴더 ID만 받습니다. 앱은 선택한 항목을 `files.get`으로 다시 읽어 폴더, 미삭제, 앱 승인, 비공유 상태와 `canAddChildren`을 확인합니다. `root`, 빈 ID, 복수 선택, 공유 또는 쓰기 불가능 폴더는 거부합니다.

## 빌드와 검증

요구 환경은 JDK 17, Android SDK 36, Build Tools 36.0.0, Gradle 9.5.0입니다.

```bash
tools/verify_drive_variant.sh
gradle --no-daemon :app:testDebugUnitTest
gradle --no-daemon :app:assembleDebug :app:assembleRelease
```

`verifyDriveVariantIdentity`가 모든 build의 `preBuild` 전에 application ID, 버전, action, authority 및 Drive-only runtime 정책을 검사합니다. `.github/workflows/build-drive-v1.yml`은 `selfrun-drive/v1.0.0-dev2` 브랜치에서 debug와 unsigned release를 빌드하고 APK 실제 package/version/label을 `aapt dump badging`으로 검증합니다.

Gradle debug APK는 GitHub-hosted runner마다 debug signing key가 달라질 수 있으므로 사용자 설치용 artifact로 배포하지 않습니다. `SELFRUN_SIGNING_PASSPHRASE` Actions secret이 구성된 경우에만 임시 `0600` pass-file을 만들고 고정 SelfRun signing identity로 release를 서명합니다. 설치 및 이후 업데이트에 사용할 최종 파일명은 다음과 같습니다.

```text
chatgpt-selfrun-drive-v1.0.0-dev2.apk
```

Actions secret이 없으면 workflow artifact에는 aligned unsigned APK와 검증 자료만 남기며 installable APK가 생성된 것처럼 취급하지 않습니다. `1.0.0-dev1`에서 CI debug APK를 설치한 기기는 해당 ephemeral signing key의 private key를 복구할 수 없으므로 `1.0.0-dev2` 고정 서명 계보로 전환할 때 한 번 삭제 후 설치가 필요할 수 있습니다. 이후 동일 고정 인증서와 증가하는 versionCode를 사용하는 빌드는 인플레이스 업데이트를 전제로 합니다.

workflow는 태그나 GitHub Release를 만들지 않고 30일 보존 artifact만 업로드합니다. 기존 `.github/workflows/build.yml`, `release-v0.2.2.yml`, 0.2.x 태그/릴리스에는 영향을 주지 않습니다.

## 검증 범위

자동 테스트는 strict commit parsing, 부분 commit 무시, Client/Job/turn/event 거부, 다중 unseen commit 모호성, terminal 의미 일치, 초기 블록 readback, root fallback 차단, Picker 단일 ID, application/action/authority 격리, legacy bootstrap 유지, Drive runtime의 `WAIT_ASSISTANT` 및 assistant observer 부재, guard와 at-most-once 제출 marker를 확인합니다.

WebView 클릭과 Android 영속 저장 사이에는 서버 idempotency key가 없으므로 크래시 전 구간에서 무손실과 엄밀한 exactly-once를 동시에 증명할 수 없습니다. 앱은 클릭 전 `SUBMISSION_STARTED`와 Web storage marker를 영속하고, 재시작 시 동일 user-turn을 확인할 수 없으면 자동 재클릭 대신 `SUBMISSION_AMBIGUOUS`로 중단하는 안전한 at-most-once 정책을 사용합니다.

OAuth 실제 성공, Drive/Docs 실제 생성, ChatGPT 실제 로그인과 두 앱 동시 설치·서비스·알림은 Android OAuth client와 배포 서명 인증서가 등록된 실기기에서 별도로 검증해야 합니다. 서명 secret이 없는 로컬 checkout에서는 signed APK 인증서 SHA-1을 확정할 수 없습니다.
