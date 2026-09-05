# SelfRun Drive

SelfRun Drive는 ChatGPT response protocol로 THINKING·ANSWERING·COMPLETE를 판정하고, Google Docs 실행턴 문서에서 완료 signal·NEXT_INPUT을 동기화하는 Android 앱입니다. 저장소의 기본 브랜치 `main`은 SelfRun Drive 정식 계보를 가리킵니다. 기존 WebView SelfRun 0.2.x의 소스와 릴리스 이력은 `selfrun-webview/main` 및 기존 `v0.2.x` 태그에 분리 보존하며 Drive 코드와 병합하지 않습니다. 두 앱은 서로 다른 Android application ID를 사용하므로 동시에 설치할 수 있습니다.

| 항목 | WebView SelfRun | SelfRun Drive |
|---|---|---|
| application ID | `com.shaterguy.chatgptselfrun` | `com.shaterguy.chatgptselfrun.drive` |
| canonical branch | `selfrun-webview/main` | `main` |
| 정식 태그 | `v0.2.x` 등 | `drive-v1.x.x` |
| 완료 기준 | ChatGPT response protocol | 현재 turnToken의 검증된 stream completion |
| 버전 계보 | 0.2.x | 1.x |

세부 릴리스 분리 규칙은 [RELEASE_CHANNELS](docs/RELEASE_CHANNELS.md)를 따릅니다.

## Global SelfRun contract

SelfRun은 특정 ChatGPT Project에 종속되지 않습니다. Drive 앱은 모든 대화에서 같은 bootstrap을 만들고 다음 canonical 운영문서 ID를 전달합니다.

```text
SELF_RUN_SKILL_DOCUMENT_ID=1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs
```

앱은 이 문서를 직접 읽거나 파싱하지 않고 Project 이름·ID·SKILL 위치도 탐색하지 않습니다. 일반 Chat에서는 global SelfRun 운영문서와 사용자 요구로 실행하고, Project 내부 대화에서는 ChatGPT가 해당 Project 규범과 global SelfRun 규범을 함께 적용합니다.

Drive Runs의 canonical 안내 경로는 `/GPT/Self Run/Runs/`입니다. 이미 저장된 Runs folder ID가 접근 가능하면 경로 이동만으로 Picker 재연결을 요구하지 않습니다.

## 앱 화면과 실행 모드

하단 메뉴는 **실행 · 기록 · 설정**이며 600dp 이상에서는 내비게이션 레일을 사용합니다. 실행 화면은 작업 제목, 현재 상태, 모델 조합, 실행 제어와 추가 지시로 구성합니다. 상세 ID·프로토콜 단계는 실행 정보에서 확인합니다.

새 작업은 프로젝트, 요구사항, 첨부파일과 실행 설정 순서입니다. 일반 채팅이 기본이며 워크에서는 첫 턴 모델 조합을 선택합니다. 일반 채팅의 첫 턴 추론 설정은 필요할 때 펼칠 수 있습니다. 요구사항과 첨부·프로젝트·모드 선택은 화면 재생성 후에도 유지합니다. 고정된 상단 메뉴와 시작 버튼 사이의 폼은 IME·글꼴 크기에 맞춰 스크롤합니다.

하이브리드 모드는 폐기했습니다. 새 실행은 CHAT/WORK만 허용합니다. 기존 실행은 저장된 현재 단계의 유효 프로필을 읽어 한 가지 일반 모드로 정규화하며 Run ID, 대화, 턴 토큰, Drive cursor와 보류 입력을 보존합니다. 확인할 수 없는 구 프로필은 추측해서 전송하지 않고 오류 상태를 표시합니다. 지난 작업 기록은 유지합니다.

## Drive 실행 흐름

1. 앱이 Run과 Google Docs 실행턴 문서를 생성하고 정확한 `documentId`를 저장합니다.
2. bootstrap에는 Drive V1 식별자, global SelfRun Skill document ID와 turn document ID가 들어가며 사용자의 원본 요구사항이 뒤에 그대로 유지됩니다.
3. ChatGPT는 global SelfRun 운영문서를 읽고 명령 수신 전·턴 종료 전 Drive signal 계약을 수행합니다.
4. 제출 직전에 현재 runId와 protocol-owned turnToken을 response protocol에 bind합니다. 제출 확인 후 VirtualDisplay Surface를 detach하고, canonical POST의 THINKING, 일반 Chat·Work final marker 또는 Pro non-empty assistant text의 ANSWERING, 상관된 `message_stream_complete`의 COMPLETE callback만 기다립니다. DOM은 composer 입력·readback·SEND·일회성 제출 확인에만 사용합니다.
5. 안정된 완료 콜백 직후 Drive 문서를 한 번 읽습니다. `TURN_COMPLETED`가 없으면 5초 간격으로 최대 5분 재확인하고, 제한시간이 지나면 현재 모델·추론 설정과 plain CONTINUE로 다음 턴을 진행합니다. 짧은 주기의 전송버튼 polling은 사용하지 않습니다.
6. Drive에서 새 `TURN_COMPLETED`를 찾으면 마지막 cursor 이후 이벤트만 소비하고 `NEXT_INPUT`과 Work profile을 적용합니다. USER_ACTION_REQUIRED와 PAUSED는 보존형 일시정지이며 DONE만 정상 종료입니다.

외부 계약은 [SelfRun Drive V1 protocol](docs/SELF_RUN_DRIVE_V1_PROTOCOL.md), Android 내부 구현은 [SelfRun Drive runtime](docs/SELF_RUN_DRIVE_RUNTIME.md)에 있습니다.

## 빌드와 검증

원격 GitHub Actions에서 JDK 17, Android SDK 36, Gradle 9.5.0을 사용합니다. 개발 브랜치의 build-drive-test.yml은 정적 검사·Android 테스트 컴파일, JVM 테스트, canonical TEST APK 생성, 기존 TEST 업데이트 및 정식판 동시 설치 검증 순서로 진행합니다.

같은 소스의 debug 계보에서 실제 Android 화면을 360/840dp, 밝은/어두운 테마, 글꼴 100/200%와 IME 조건으로 캡처합니다. 실행·일시정지·완료·오류·예약·잠금 상태 및 초안 보존을 검증합니다. 캡처는 ui-evidence에 source SHA·변형과 함께 기록하며, 사용자 배포본은 고정 TEST 인증서로 서명한 qaApp입니다.

성공한 동일 canonical APK와 화면 증거를 immutable artifact ref에 게시합니다. deliverables/direct-apk.txt의 실제 APK URL을 내려받아 SHA-256과 크기를 다시 확인하며, 사용자 전달에는 해당 직접 APK 링크를 사용합니다. TEST application ID는 com.shaterguy.chatgptselfrun.drive.test로 정식판과 분리됩니다.

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
