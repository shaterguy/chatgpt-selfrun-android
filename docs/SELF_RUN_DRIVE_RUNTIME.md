# SelfRun Drive runtime implementation

이 문서는 `SELF_RUN_ORCHESTRATION_SKILL`에서 분리한 Android 내부 구현 정보를 저장소 유지보수 관점에서 기록합니다. AI SelfRun 실행 규범의 권위 원본이 아닙니다.

## 책임 경계

Android 앱은 Run ID·실행턴 문서를 만들고 같은 ChatGPT conversation의 composer에 bootstrap/CONTINUE를 제출하며 Drive signal을 polling합니다. 앱은 SelfRun 운영문서의 내용을 읽거나 파싱하지 않고, `SelfRunProtocol.SELF_RUN_SKILL_DOCUMENT_ID` 값을 prompt metadata로 전달하기만 합니다. Project 판정·Project SKILL 탐색·규칙 충돌 해석도 수행하지 않습니다.

## WebView host

Drive V1은 background WebView를 private virtual display에 호스팅하는 기존 구조를 유지합니다. 현재 기준 virtual display는 1440×900, 160 dpi이며 wide viewport/overview mode를 사용합니다. WebView cookie jar는 앱의 로그인 세션과 공유합니다. 고정 Windows user-agent를 강제하는 방식은 사용하지 않습니다.

WebView는 assistant completion을 관찰하지 않습니다. 역할은 새 conversation 진입, 저장된 canonical conversation URL 복구, composer 탐색과 명령 제출입니다. renderer 소실이나 composer 재획득 실패는 저장된 conversation URL을 이용한 복구 대상으로 처리합니다.

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

`SelfRunStore`는 Run ID, CHAT/WORK mode, canonical conversation URL, Drive account/base folder ID, Job folder ID, turn document ID, signal cursor, phase와 재개에 필요한 상태를 영속합니다. pause에는 RUN_ID/origin/cause/pausedFromPhase/cursor/Drive version·modifiedTime/stable anchor ID를 함께 저장합니다. TURN_COMPLETED에는 pending cursor와 completion fingerprint를 저장하여 같은 completion 또는 process restart가 동일 continuation click을 반복하지 않도록 합니다. 폴더 이름이나 표시 경로가 바뀌어도 저장된 Drive object ID가 접근 가능하면 그대로 사용합니다.

NEXT_INPUT 원문은 실행에 필요한 pending Drive signal/active command의 수명 동안만 app-private state에 존재합니다. `SelfRunHistoryStore`에는 `NEXT_INPUT_B64URL` 값을 redaction하여 복사하며 run log/notification/status에는 payload 원문을 기록하지 않습니다. continuation DOM은 completion cursor, pause anchor ID와 NEXT fingerprint에서 만든 stable marker를 사용하며 `clicked` marker가 있으면 retry/restart에서도 composer를 다시 클릭하지 않습니다.

재개는 `PHASE_RESUME_BASELINE`에서 latest signal을 단순 baseline하지 않습니다. Drive signal로 생긴 pause는 pause anchor 이후 event를 수집하고 `DriveResumePolicy`가 completion/blocking/DONE/no-new-signal/UI-manual case를 판정합니다. 반면 ChatGPT 로그인·OAuth·Drive 재연결 같은 앱 내부 prerequisite pause는 `resumeNeedsContinuation=false`를 영속하고 Resume 시 `pauseAnchorPhase`로 직접 복귀하여 bootstrap 전이나 turn document 생성 전에도 빈 document를 polling하지 않습니다. Drive read 실패는 plain CONTINUE fallback을 만들지 않습니다.

## OAuth와 Drive 객체

승인 scope는 `https://www.googleapis.com/auth/drive.file` 하나입니다. 사용자가 Picker로 선택한 Runs 폴더와 앱이 그 아래 생성한 객체만 사용합니다. global SelfRun 운영문서의 ID를 bootstrap에 넣기 위해 그 문서를 앱이 직접 다운로드하지 않습니다.

canonical 안내 경로는 `/GPT/Self Run/Runs/`이며, 기존 Runs 객체 ID `1LaIjBACRA4bgTblTOHOki5OF39Cyxi4c`를 유지하는 것이 migration 전제입니다.

## 패키징과 서명

Drive 계보의 Android application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. WebView SelfRun 0.2.x 계보와 별도 설치·버전·릴리스 채널을 유지합니다. 개발 branch의 최종 사용자 테스트 APK도 CI debug key가 아니라 `tools/sign_release.sh`가 검증하는 고정 SelfRun 인증서로 서명해야 업데이트 설치 계보를 유지할 수 있습니다.

서명 인증서 기대 SHA-256, 파생 방식과 정식 릴리스 절차의 세부사항은 [SIGNING](SIGNING.md), `tools/sign_release.sh`, `release-drive-v1.yml`을 권위 원본으로 사용합니다.
