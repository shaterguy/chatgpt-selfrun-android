# SelfRun Drive runtime implementation

이 문서는 `SELF_RUN_ORCHESTRATION_SKILL`에서 분리한 Android 내부 구현 정보를 저장소 유지보수 관점에서 기록합니다. AI SelfRun 실행 규범의 권위 원본이 아닙니다.

## 책임 경계

Android 앱은 Run ID·Run 작업폴더·실행턴 문서를 만들고 같은 ChatGPT conversation의 composer에 bootstrap/CONTINUE를 제출하며 STOP/SEND DOM 변화로 답변 완료를 감지한 뒤 Run 폴더의 Drive signal document를 동기화합니다. 앱은 SelfRun 운영문서의 내용을 읽거나 파싱하지 않고, `SelfRunProtocol.SELF_RUN_SKILL_DOCUMENT_ID` 값을 prompt metadata로 전달하기만 합니다. Project 판정·Project SKILL 탐색·규칙 충돌 해석도 수행하지 않습니다.

신규 Run의 bootstrap에는 `DRIVE_JOB_FOLDER_ID`가 포함됩니다. ChatGPT는 이 폴더 바로 아래에 한 signal당 한 native Google Doc을 생성합니다. 실행턴 문서는 초기 Run 객체·첨부파일 경계·legacy 호환을 위해 계속 생성하지만 신규 transport의 signal append target이 아닙니다.

## WebView host와 UI 보정

Drive V1은 background WebView를 private virtual display에 호스팅하는 구조를 유지하되, v1.2.3 개발선부터 desktop 고정 1440×900/160 dpi 대신 visible calibration WebView와 동일한 mobile WebView 정책을 사용합니다. `WebViewConfig.applyAutomation`은 wide viewport/overview mode를 끄며, background virtual display는 사용자가 마지막으로 보정한 mobile screen width·height·devicePixelRatio에서 계산한 크기와 density를 우선 사용합니다. 유효한 보정 viewport가 없으면 현재 Android 기기의 display metrics를 portrait 기준으로 사용합니다.

메인 화면의 `웹 UI 보정`은 사용자가 실제 ChatGPT 화면을 직접 조작해 자동화 대상의 DOM 특징을 다시 확보하는 복구 경로입니다. 현재 목적 키는 일반채팅/Work 모드 항목, Work 모델 항목, Work 추론 정도 항목, 프로젝트 새 대화 진입·composer·send, 일반 새 대화 진입·composer·send입니다. 단순 메뉴/선택 위치는 사용자의 터치 후 Android 확인 버튼으로 확정하고, 새 대화 제출은 composer input과 submit 행위를 함께 관찰해 확정합니다.

보정 프로파일은 app-private SharedPreferences를 내구 원본으로 유지하고 같은 ChatGPT origin의 Web Storage에도 주입합니다. 런타임 DOM 코드는 보정 target을 우선 사용하되 매칭하지 못하면 v1.2.2의 기존 semantic/testid/role 휴리스틱으로 후퇴합니다. 보정 로그는 purpose별 arm/candidate/confirm/save/reset 상태만 기록하며 사용자가 테스트로 입력한 문구 내용은 기록하지 않습니다. `addJavascriptInterface`는 사용하지 않습니다.

WebView는 assistant 메시지 본문이나 제어문구를 관찰하지 않습니다. 역할은 새 conversation 진입, 저장된 canonical conversation URL 복구, composer·STOP/SEND 영역 탐색, 명령 제출과 완료 상태 감지입니다. 정확한 전문 readback과 활성 SEND를 확인한 제출 클릭과 같은 JavaScript 실행에서 `MutationObserver`를 설치하고, 클릭 결과를 곧바로 완료 관찰 상태로 전이합니다. 전송 뒤 별도 버튼 polling은 하지 않습니다. STOP을 처음 관찰하면 현재 run/token에만 유효한 native callback으로 내구 증거를 기록하고, 그 뒤 SEND/비활성 SEND/idle composer 상태가 나타나면 5초 단발 타이머를 시작합니다. 타이머 종료 시 `controlState()`를 한 번 더 호출해 여전히 완료 상태인 경우에만 Observer를 해제하고 native callback을 보냅니다. STOP이 돌아오면 타이머를 취소하고 계속 관찰합니다. renderer 소실이나 composer 재획득 실패 뒤 idle baseline은 이 내구 STOP 증거가 있을 때만 허용합니다.

## Foreground Service와 WakeLock

실행 중 Job은 Android Foreground Service로 유지합니다. WakeLock은 Drive 요청, WebView 복구, 명령 제출처럼 실제 작업이 필요한 짧은 구간에만 사용하고 단순 polling 대기·guard 대기에는 유지하지 않는 것을 원칙으로 합니다. TURN_COMPLETION observer가 arm된 뒤에는 `WebView.onPause()`로 안전한 렌더링·애니메이션 처리를 멈추되 JavaScript observer와 15초 healthcheck는 유지하며, 다음 상호작용·복구 단계에서만 `onResume()`합니다. 알림 채널, pause/resume 동작과 서비스 상태는 `SelfRunService`와 `NotificationHelper`가 소유합니다.

## 완료 감지와 Drive 동기화 state machine

현재 구현의 주요 값은 다음과 같습니다.

- 답변 완료 안정성 재확인: 5초 단발 타이머
- DOM 완료 뒤 Drive 첫 확인: 즉시 1회
- signal 부재 시 Drive 재확인: 5초
- signal 대기 제한시간: 5분
- 네트워크 복구 backoff 배열: 15초, 30초, 60초, 120초, 240초
- authoritative progress: Run 폴더 signal document의 정렬된 logical cursor

정상 턴 완료는 Drive polling으로 판정하지 않습니다. native callback이 `WAIT_TURN_COMPLETION`의 현재 run/token과 일치할 때만 `POST_DOM_DRIVE_SYNC`로 전이합니다.

`DriveApiClient.getPollMetadata()`는 기존 실행턴 문서의 정확한 parent와 RUN_ID를 기준으로 같은 job folder의 native Google Docs를 조회합니다. `DriveSignalDocumentTransport`가 현재 RUN_ID의 canonical signal title만 선별하고 다음 순서로 정렬합니다.

1. Drive `createdTime`
2. title의 `yyyy.mm.dd | hh:mm:ss`
3. Drive `fileId`

최신 candidate의 `fileId + modifiedTime`을 synthetic version marker로 사용하여 기존 `SelfRunService`의 version-change gating을 재사용합니다. 최신성 순위 자체는 `createdTime → title timestamp → fileId`로 고정하지만, NEXT_INPUT 문서는 제목 생성 뒤 같은 fileId의 본문이 추가되므로 `modifiedTime` 변화도 재조회 트리거로 사용합니다. 새 candidate 또는 같은 candidate의 본문 갱신이 있으면 `readDocumentSnapshot()`은 실제 실행턴 문서 대신 정렬된 signal document 목록을 기존 append-only signal line처럼 합성합니다. 이렇게 기존 `DriveSignalParser`, physical cursor, dominance, Work profile, NEXT_INPUT, pause/resume 상태전이는 유지합니다.

`NEXT_INPUT_B64URL=BODY` marker가 없는 signal은 제목만 사용하므로 Docs 본문을 열지 않습니다. marker가 있는 문서만 Docs API로 본문을 읽고, 정확한 `NEXT_INPUT_B64URL=<Base64URL>` 한 줄을 제목에 materialize한 뒤 기존 parser에 넘깁니다. 제목은 canonical이지만 본문이 아직 쓰이지 않았거나 malformed인 NEXT_INPUT 문서는 정상 signal로 소비하지 않고 현재 합성 로그에서 건너뜁니다. 같은 fileId의 본문이 완성되면 `modifiedTime`이 synthetic version을 바꾸므로 다음 동기화에서 다시 검증합니다. 이후 더 최신한 정상 signal이 생성된 경우에도 오래된 malformed BODY 문서가 새 signal 처리를 영구 차단하지 않습니다. 다른 Run, 다른 parent, shared/trashed 항목도 fail closed합니다.

`RESUME_BASELINE`도 같은 Run-folder signal 목록을 다시 합성해 최신 cursor 이후의 completion을 확인합니다. signal 문서가 아직 없으면 5초 간격으로 최대 5분 재확인합니다. STOP/SEND 완료 감지에 짧은 주기의 반복 polling은 사용하지 않습니다.

Drive write/readback, 생성 도중 중단, 409/404, 오래된 실행과 새 실행의 경합은 영속 상태와 실제 객체 readback을 기준으로 복구합니다. 구체 상태 전이와 race 방지 lock은 `SelfRunService`, `SelfRunStore`, `DriveApiClient`, `DriveSignalDocumentTransport`, `DriveSignalParser`의 현재 코드가 권위 원본입니다.

## 로컬 영속 상태

`SelfRunStore`는 Run ID, CHAT/WORK mode, canonical conversation URL, Drive account/base folder ID, Job folder ID, turn document ID, logical signal cursor, phase와 재개에 필요한 상태를 영속합니다. 폴더 이름이나 표시 경로가 바뀌어도 저장된 Drive object ID가 접근 가능하면 그대로 사용합니다.

웹 UI 보정 상태는 실행 상태와 분리된 `WebUiCalibrationStore`가 소유합니다. 따라서 개별 SelfRun 시작·종료·이력 정리로 보정 프로파일이 초기화되지 않으며, 사용자가 보정 화면에서 명시적으로 전체 초기화를 실행할 때만 제거합니다.

protocol 0.2.0의 NEXT_INPUT은 새 전용 resume state를 만들지 않고 기존 `pendingDriveSignalRaw`에 포함된 materialized completion을 내구 원본으로 사용합니다. command prompt를 만들 때 strict decode하며, history snapshot에는 payload token을 redaction합니다. 수동 재개에서는 `RESUME_BASELINE` 재조회에서 기존 cursor 이후 새 completion만 payload 후보로 사용합니다.

## OAuth와 Drive 객체

권한은 목적별 최소 조합으로 분리합니다.

- `drive.file`: Picker로 선택한 Runs 폴더와 앱이 생성한 Run 폴더·실행턴 문서·첨부파일의 쓰기와 관리
- `drive.metadata.readonly`: ChatGPT가 다른 OAuth client context에서 생성한 signal document의 metadata를 현재 Run 폴더 안에서 찾기
- `documents.readonly`: NEXT_INPUT marker가 있는 signal document의 본문만 읽기

전체 Drive 쓰기 scope인 `drive`는 요청하지 않습니다. cross-app read scope가 넓은 metadata/docs visibility를 제공하더라도 런타임 후보는 저장된 exact job folder ID, exact RUN_ID, native Doc, canonical title 조건으로 다시 제한합니다. token과 NEXT_INPUT payload는 로그에 기록하지 않습니다.

기존 설치에서 새 read-only scope가 아직 승인되지 않은 경우 Google Identity authorization resolution이 필요할 수 있습니다. 권한 승인 이후에는 기존 Runs 폴더 binding과 앱-owned Drive 객체를 그대로 유지합니다.

canonical 안내 경로는 `/GPT/Self Run/Runs/`이며, 기존 Runs 객체 ID `1LaIjBACRA4bgTblTOHOki5OF39Cyxi4c`를 유지하는 것이 migration 전제입니다.

## 패키징과 서명

Drive 계보의 Android application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. WebView SelfRun 0.2.x 계보와 별도 설치·버전·릴리스 채널을 유지합니다. 개발 branch의 최종 사용자 테스트 APK도 CI debug key가 아니라 `tools/sign_release.sh`가 검증하는 고정 SelfRun 인증서로 서명해야 업데이트 설치 계보를 유지할 수 있습니다.

서명 인증서 기대 SHA-256, 파생 방식과 정식 릴리스 절차의 세부사항은 [SIGNING](SIGNING.md), `tools/sign_release.sh`, `release-drive-v1.yml`을 권위 원본으로 사용합니다.
