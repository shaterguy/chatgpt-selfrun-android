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

## SESSION_BOUND

정확한 초기 블록의 Job ID를 확인하고 문서 쓰기 접근에 성공한 직후 append한다.

```text
[SELF_RUN_DRIVE_BOUND_V1]
PROTOCOL_VERSION=1
CLIENT_ID=SELFRUN_DRIVE_ANDROID
JOB_ID=<JOB_ID>
TURN=<TURN>
STATE=SESSION_BOUND
BOUND_AT=<ISO 8601 with offset>
[/SELF_RUN_DRIVE_BOUND_V1]
```

이는 쓰기 연결 확인일 뿐 continuation 조건이 아니다. 진행 상태 `ANALYZING`, `IMPLEMENTING`, `VERIFYING`, `FINALIZING`도 continuation을 발생시키지 않는다. 진행 상태는 실제 단계 전이에만 기록하며 assistant 답변, 내부 추론, 전체 로그 또는 제어 신호 예시를 누적하지 않는다.

## 턴 완료 commit

현재 턴의 모든 작업과 검증이 끝난 마지막 단계에 완전한 블록 하나를 append한다.

```text
[SELF_RUN_DRIVE_COMMIT_V1]
PROTOCOL_VERSION=1
CLIENT_ID=SELFRUN_DRIVE_ANDROID
JOB_ID=<JOB_ID>
TURN=<TURN>
EVENT_SEQ=<strictly increasing integer>
COMMIT_KIND=<CONTINUE|DONE|PAUSE|USER_ACTION_REQUIRED|ERROR>
STATE=<TURN_COMMITTED|RUN_DONE|RUN_PAUSED|USER_ACTION_REQUIRED|RUN_ERROR>
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
| ERROR | RUN_ERROR | `[SELF_RUN_ERROR <RUN_ID> REASON=<SHORT_CODE>]` |

`SELF_RUN_ERROR`는 Drive V1 조건에서만 추가로 허용되는 terminal 신호다. legacy `0.2.x` 실행에는 출력하지 않으며, legacy는 기존 `NEXT`, `DONE`, `USER_ACTION_REQUIRED`, `PAUSE` 문법만 유지한다.

Drive 전용 유사 신호를 만들지 않는다. 기존 commit을 수정·삭제하지 않고 새 commit을 뒤에 append한다. 마지막 순서는 반드시 다음과 같다.

```text
Drive commit 작성
→ 같은 documentId의 본문 readback
→ 동일한 SelfRun 신호를 ChatGPT 답변 말미에 출력
```

Drive V1 앱은 답변 DOM의 신호를 진행 기준으로 사용하지 않지만 legacy 호환을 위해 답변 출력은 유지한다.

## Android 수락 규칙

앱은 시작/종료 marker, 모든 필수 ASCII key, `CLIENT_ID`, Job ID, expected turn, 증가한 event sequence, offset timestamp, 정확히 한 줄의 실제 SelfRun signal, kind/state/signal 의미 일치를 모두 확인한다. 부분 write는 무시한다. 예상보다 작은 턴과 소비한 event는 stale로 무시하고 미래 턴, unknown/duplicate/confusable key, overflow, 복수 unseen commit은 protocol error로 중단한다.

`CONTINUE`는 event를 먼저 영속 저장하고 최초 감지 시각 기준 120초 guard 후 `SELF_RUN_DRIVE_COMMIT_ID=<JOB>:<TURN>:<EVENT_SEQ>` marker를 포함한 continuation을 한 번 제출한다. click 전 `SUBMISSION_STARTED`를 동기 저장한다. 재시작은 같은 conversation의 user message marker만 확인하며 불확실할 때 자동 재클릭하지 않고 사용자 확인 상태로 전환한다. 이 방식은 안전한 at-most-once 정책이며, WebView click에 서버 idempotency가 없으므로 click 직전 크래시에서 무손실과 엄밀한 exactly-once를 동시에 보장한다고 주장하지 않는다.

`DONE`, `PAUSE`, `USER_ACTION_REQUIRED`, `ERROR`에서는 continuation을 제출하지 않는다.

## 생성 원자성

Job 폴더는 Drive `files.generateIds`로 ID를 먼저 발급하고 로컬에 동기 영속한 뒤 그 ID와 명시적 `parents=[driveRunsBaseFolderId]`로 생성한다. 재시작은 알려진 ID의 `files.get` 또는 같은 ID 생성만 사용한다.

Google Docs 네이티브 문서는 사전 발급 ID를 지원하지 않는다. Android는 `DOCUMENT_CREATING`을 먼저 영속하고 `files.create` 응답의 ID만 저장한다. timeout, 응답 유실, 해석 불가 등 결과가 불명확하면 `DRIVE_DOCUMENT_CREATE_RESULT_UNKNOWN` 보존형 중단으로 전환하고 파일 목록, 이름, Job ID 검색이나 재생성을 하지 않는다.
