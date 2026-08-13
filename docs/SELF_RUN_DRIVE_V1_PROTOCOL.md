# SelfRun orchestration: legacy 0.2.x + Drive V1

이 문서는 저장소 코드와 함께 관리되는 Drive V1 운영 규격입니다. 외부의 공식 `SELF_RUN_ORCHESTRATION_SKILL`에는 같은 조건부 규칙을 반영하고, 수정 후 그 문서의 file ID, parent, MIME type, modifiedTime과 본문을 readback해야 합니다. 앱이나 CI는 외부 운영문서를 자동 수정하지 않습니다.

## 모드 판별

다음 세 값이 모두 있을 때만 Drive V1이다.

```text
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_PROTOCOL_VERSION=1
DRIVE_TURN_DOCUMENT_ID=<value>
```

하나라도 없으면 legacy 0.2.x로 처리한다. legacy bootstrap과 기존 답변 말미 신호 문법, WebView 기반 완료 감시는 그대로 유지하며 Drive 문서 생성이나 갱신을 요구하지 않는다.

## Drive V1 소유권

Android 앱이 ChatGPT bootstrap 전 다음 순서로 Job 폴더와 실행턴 Google Docs 문서를 직접 생성·초기화·readback한다.

```text
DRIVE_ACCOUNT_CHECK
DRIVE_BASE_FOLDER_CHECK
JOB_ID_CREATE
DRIVE_JOB_FOLDER_CREATE
DRIVE_TURN_DOCUMENT_CREATE
DRIVE_DOCUMENT_INIT
DRIVE_DOCUMENT_READBACK
BOOTSTRAP
BOOTSTRAP_MODEL
BOOTSTRAP_REASONING
BOOTSTRAP_SEND
WAIT_DRIVE_COMMIT
```

ChatGPT 실행 측은 bootstrap에 전달된 정확한 `DRIVE_TURN_DOCUMENT_ID` 또는 URL만 사용한다. Job 폴더나 문서를 만들거나 이름, 경로, Job ID로 검색하지 않는다.

앱의 bootstrap은 위 판별 필드와 현재 Job의 ID·URL·예상 턴 등 실행별 metadata, 그리고 사용자의 실제 작업 지시만 전달한다. 아래 실행 규격 전체를 매 bootstrap에 복제하지 않는다. Drive V1을 판별한 ChatGPT 실행 측은 공식 `SELF_RUN_ORCHESTRATION_SKILL`의 Drive V1 절을 권위 규격으로 적용한다.

## 최초 bootstrap 제출 확인(Android 구현)

새 conversation URL이 생긴 직후에는 정확한 bootstrap 사용자 턴 DOM이 아직 나타나지 않을 수 있다. 앱은 이를 즉시 제출 실패로 판단하지 않고 conversation URL을 먼저 영속 저장한 뒤 최대 120초 동안 정확한 사용자 턴을 확인한다. 이 대기 중 최초 prompt를 다시 클릭하거나 전송하지 않으며 assistant 답변, 생성 상태, 중지 버튼 또는 streaming DOM을 확인하지 않는다. 제한 시간 안에 사용자 턴을 확인하지 못하거나 conversation ID가 바뀌면 `BOOTSTRAP_SUBMISSION_RESULT_UNKNOWN` 또는 conversation mismatch 보존형 일시정지로 전환한다.

## 기존 SelfRun 실행 규칙 유지

Drive V1에서도 ChatGPT가 작업 지시, 이전 HANDOFF와 기존 SelfRun 신호의 의미를 해석한다. 기존 턴 수행 방식은 바뀌지 않는다. Android 앱은 중간 연결·진행 상태를 판단하지 않으며, 차이는 현재 턴이 완전히 끝난 뒤 답변을 출력하기 직전에 아래 완료 commit을 실행턴 문서에 추가한다는 점뿐이다.

## 턴 완료 commit

현재 턴의 모든 작업·검증·HANDOFF와 출력할 기존 SelfRun 신호가 확정된 마지막 단계에 완전한 블록 하나를 append한다.

```text
[SELF_RUN_DRIVE_COMMIT_V1]
PROTOCOL_VERSION=1
CLIENT_ID=SELFRUN_DRIVE_ANDROID
JOB_ID=<JOB_ID>
TURN=<TURN>
EVENT_SEQ=<strictly increasing integer>
COMMIT_KIND=<CONTINUE|DONE|PAUSE|USER_ACTION_REQUIRED>
STATE=<TURN_COMMITTED|RUN_DONE|RUN_PAUSED|USER_ACTION_REQUIRED>
COMMITTED_AT=<ISO 8601 with offset>
SIGNAL_BEGIN
<exactly one existing SelfRunProtocol signal line>
SIGNAL_END
[/SELF_RUN_DRIVE_COMMIT_V1]
```

매핑은 다음과 같다.

| Commit kind | State | 실제 신호 |
|---|---|---|
| CONTINUE | TURN_COMMITTED | `[SELF_RUN_NEXT <RUN_ID> ...]` |
| DONE | RUN_DONE | `[SELF_RUN_DONE <RUN_ID>]` |
| PAUSE | RUN_PAUSED | `[SELF_RUN_PAUSE <RUN_ID> ...]` |
| USER_ACTION_REQUIRED | USER_ACTION_REQUIRED | `[SELF_RUN_USER_ACTION_REQUIRED <RUN_ID> <ACTION>]` |
Drive 전용 유사 신호를 만들지 않는다. 기존 commit을 수정·삭제하지 않고 새 commit을 뒤에 append한다. 마지막 순서는 반드시 다음과 같다.

```text
Drive commit 작성
→ 같은 documentId의 본문 readback
→ 동일한 SelfRun 신호를 ChatGPT 답변 말미에 출력
```

Drive V1 앱은 답변 DOM의 신호를 진행 기준으로 사용하지 않지만 legacy 호환을 위해 답변 출력은 유지한다.

## Android 수락 규칙

앱은 시작/종료 marker, 모든 필수 ASCII key, `CLIENT_ID`, Job ID, expected turn, 증가한 event sequence, offset timestamp, 정확히 한 줄의 실제 SelfRun signal, kind/state/signal 의미 일치를 모두 확인한다. 부분 write는 무시한다. 예상보다 작은 턴과 소비한 event는 stale로 무시하고 미래 턴, unknown/duplicate/confusable key, overflow, 복수 unseen commit은 protocol error로 중단한다.

`CONTINUE`는 event를 먼저 영속 저장하고 최초 감지 시각 기준 120초 guard 후 기존과 동일한 `[SELF_RUN_CONTINUE <RUN_ID>]` 한 줄만 제출한다. Drive commit ID는 앱 내부 중복 방지 표식으로만 사용하며 ChatGPT 입력문에 추가하지 않는다. click 전 `SUBMISSION_STARTED`를 동기 저장하고, 불확실할 때 자동 재클릭하지 않고 사용자 확인 상태로 전환한다. 이 방식은 안전한 at-most-once 정책이며, WebView click에 서버 idempotency가 없으므로 click 직전 크래시에서 무손실과 엄밀한 exactly-once를 동시에 보장한다고 주장하지 않는다.

`DONE`, `PAUSE`, `USER_ACTION_REQUIRED`에서는 continuation을 제출하지 않는다.

## 생성 원자성

Job 폴더는 Drive `files.generateIds`로 ID를 먼저 발급하고 로컬에 동기 영속한 뒤 그 ID와 명시적 `parents=[driveRunsBaseFolderId]`로 생성한다. 재시작은 알려진 ID의 `files.get` 또는 같은 ID 생성만 사용한다.

Google Docs 네이티브 문서는 사전 발급 ID를 지원하지 않는다. Android는 `DOCUMENT_CREATING`을 먼저 영속하고 `files.create` 응답의 ID만 저장한다. timeout, 응답 유실, 해석 불가 등 결과가 불명확하면 `DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN` 보존형 중단으로 전환하고 파일 목록, 이름, Job ID 검색이나 재생성을 하지 않는다.
