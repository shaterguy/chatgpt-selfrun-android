# SelfRun Drive runtime implementation

이 문서는 `SELF_RUN_ORCHESTRATION_SKILL`에서 분리한 Android 내부 구현 정보를 저장소 유지보수 관점에서 기록합니다. AI SelfRun 실행 규범의 권위 원본이 아닙니다.

## 책임 경계

Android 앱은 Run ID·실행턴 문서를 만들고 같은 ChatGPT conversation의 composer에 bootstrap/CONTINUE를 제출하며 Drive signal을 polling합니다. 앱은 SelfRun 운영문서의 내용을 읽거나 파싱하지 않고, `SelfRunProtocol.SELF_RUN_SKILL_DOCUMENT_ID` 값을 prompt metadata로 전달하기만 합니다. Project 판정·Project SKILL 탐색·규칙 충돌 해석도 수행하지 않습니다.

## WebView host

Drive V1은 background WebView를 private virtual display에 호스팅하는 기존 구조를 유지합니다. 현재 기준 virtual display는 1440×900, 160 dpi이며 wide viewport/overview mode를 사용합니다. WebView cookie jar는 앱의 로그인 세션과 공유합니다. 고정 Windows user-agent를 강제하는 방식은 사용하지 않습니다.

WebView는 assistant completion을 관찰하지 않습니다. 역할은 새 conversation 진입, 저장된 canonical conversation URL 복구, composer 탐색과 명령 제출입니다. renderer 소실이나 composer 재획득 실패는 저장된 conversation URL을 이용한 복구 대상으로 처리합니다.

새 Run은 이전 Run의 background WebView를 재사용하지 않는 것을 불변조건으로 합니다. `SelfRunService.onStartCommand()`가 영속 `runId`와 현재 runtime `runId`의 차이를 감지하면 기존 automation callback을 무효화한 직후 `cleanupWebView()`로 이전 Run의 WebView/virtual display를 폐기하고 새 Run ID를 채택합니다. 따라서 `SelfRunNewActivity`의 `stopService()` 직후 새 start 요청이 서비스 파괴보다 먼저 들어오는 경우에도 이전 프로젝트 conversation route가 새 bootstrap으로 전달되지 않습니다. 이 격리는 Android 서비스의 비동기 stop/destroy 타이밍에 의존하지 않습니다.

## Foreground Service와 WakeLock

실행 중 Job은 Android Foreground Service로 유지합니다. WakeLock은 Drive 요청, WebView 복구, 명령 제출처럼 실제 작업이 필요한 짧은 구간에만 사용하고 단순 polling 대기·guard 대기에는 유지하지 않는 것을 원칙으로 합니다. 알림 채널, pause/resume 동작과 서비스 상태는 `SelfRunService`와 `NotificationHelper`가 소유합니다.

## Drive polling state machine

현재 구현의 주요 값은 다음과 같습니다.

- 정상 Drive polling 기본 주기: 60초
- TURN_COMPLETED 후 continuation UI guard: 45초
- bootstrap/CONTINUE 제출 후 새 ACK가 없을 때 재제출: 5분
- 복구 backoff 배열: 15초, 30초, 60초, 120초, 240초
- `modifiedTime`: 읽기 최적화 힌트
- authoritative progress: append-only SelfRun signal cursor

retry 횟수와 누적 시간을 terminal 조건으로 사용하지 않습니다. Drive write/readback, 생성 도중 중단, 409/404, 오래된 실행과 새 실행의 경합은 영속 상태와 실제 객체 readback을 기준으로 복구합니다. 구체 상태 전이와 race 방지 lock은 `SelfRunService`, `SelfRunStore`, `DriveApiClient`, `DriveSignalParser`의 현재 코드가 권위 원본입니다.

## 로컬 영속 상태

`SelfRunStore`는 Run ID, CHAT/WORK mode, canonical conversation URL, Drive account/base folder ID, Job folder ID, turn document ID, signal cursor, phase와 재개에 필요한 상태를 영속합니다. 폴더 이름이나 표시 경로가 바뀌어도 저장된 Drive object ID가 접근 가능하면 그대로 사용합니다.

protocol 0.2.0의 NEXT_INPUT은 새 전용 resume state를 만들지 않고 기존 `pendingDriveSignalRaw`에 포함된 completion을 내구 원본으로 사용합니다. command prompt를 만들 때만 strict decode하며, history snapshot에는 payload token을 redaction합니다. 수동 재개에서는 `RESUME_BASELINE` 재조회에서 기존 cursor 이후 새 completion만 payload 후보로 사용합니다.

## OAuth와 Drive 객체

승인 scope는 `https://www.googleapis.com/auth/drive.file` 하나입니다. 사용자가 Picker로 선택한 Runs 폴더와 앱이 그 아래 생성한 객체만 사용합니다. global SelfRun 운영문서의 ID를 bootstrap에 넣기 위해 그 문서를 앱이 직접 다운로드하지 않습니다.

canonical 안내 경로는 `/GPT/Self Run/Runs/`이며, 기존 Runs 객체 ID `1LaIjBACRA4bgTblTOHOki5OF39Cyxi4c`를 유지하는 것이 migration 전제입니다.

## 패키징과 서명

Drive 계보의 Android application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. WebView SelfRun 0.2.x 계보와 별도 설치·버전·릴리스 채널을 유지합니다. 개발 branch의 최종 사용자 테스트 APK도 CI debug key가 아니라 `tools/sign_release.sh`가 검증하는 고정 SelfRun 인증서로 서명해야 업데이트 설치 계보를 유지할 수 있습니다.

서명 인증서 기대 SHA-256, 파생 방식과 정식 릴리스 절차의 세부사항은 [SIGNING](SIGNING.md), `tools/sign_release.sh`, `release-drive-v1.yml`을 권위 원본으로 사용합니다.
