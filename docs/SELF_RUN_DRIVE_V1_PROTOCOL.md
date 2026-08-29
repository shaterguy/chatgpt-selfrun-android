# SelfRun Drive V1 protocol

이 문서는 Android 앱 구현과 Drive V1 외부 인터페이스 계약을 설명합니다. SelfRun의 AI 역할 전환, HANDOFF, NEXT/DONE/PAUSE/USER_ACTION_REQUIRED 의미와 완료 판정은 Google Drive의 canonical `SELF_RUN_ORCHESTRATION_SKILL` 문서가 소유합니다.

## Global SelfRun bootstrap

SelfRun Drive는 모든 대화에서 같은 bootstrap을 사용합니다. 앱은 현재 ChatGPT 대화가 Project인지 판정하지 않으며 Project 이름·ID·SKILL 위치를 탐색하지 않습니다.

신규 signal-document transport를 사용하는 bootstrap은 Run의 작업폴더 ID를 반드시 ChatGPT에 전달합니다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_BOOTSTRAP 0.2.0 <RUN_ID> MODE=<CHAT|WORK>]
SELF_RUN_CLIENT=DRIVE_V1
SELF_RUN_SKILL_DOCUMENT_ID=1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs
DRIVE_TURN_DOCUMENT_ID=<documentId>
DRIVE_JOB_FOLDER_ID=<jobFolderId>

이 실행은 SelfRun이다.

실질 작업을 시작하기 전에 위 SelfRun 운영문서 ID가 가리키는 Google Drive 문서의 현재 최신 메타데이터와 전체 내용을 읽고 SelfRun 실행 규범으로 적용한다.

현재 conversation이 ChatGPT Project 내부의 대화라면 해당 Project의 프로젝트 지침과 그 지침이 지정하는 SKILL·운영문서도 함께 적용한다. 프로젝트의 업무·도메인·데이터·산출물·프로젝트 고유 운영 규칙은 해당 Project 규범을 따른다.

[요구사항]
<사용자가 앱에 입력한 원본 요구사항>
```

`DRIVE_JOB_FOLDER_ID`는 ChatGPT가 signal Google Doc을 생성해야 하는 유일한 Run 폴더 주소입니다. 신규 transport에서 ChatGPT는 signal 문서를 이 ID가 가리키는 폴더 바로 아래에만 생성합니다. `DRIVE_TURN_DOCUMENT_ID`는 초기 실행문서·첨부파일 경계·구버전 호환을 위해 유지하지만 신규 signal의 쓰기 대상이 아닙니다.

신규 transport의 실행턴 Google Doc은 초기화 시 비어 있습니다. ChatGPT에 제공된 Drive 도구가 기존 Google Doc을 지정 parent로 직접 복사할 수 있으면, 이 빈 `DRIVE_TURN_DOCUMENT_ID` 문서를 템플릿으로 사용하여 최종 signal title과 `DRIVE_JOB_FOLDER_ID`를 동시에 지정하는 복사 경로를 우선합니다. NEXT_INPUT이 없는 signal은 이 복사만으로 완결되며, `NEXT_INPUT_B64URL=BODY`가 있는 signal만 복사된 문서의 본문을 추가 작성합니다. 직접 parent 지정 복사·생성이 불가능한 도구에서는 생성 후 정확한 parent로 이동하고 readback하기 전까지 기록 완료로 간주하지 않습니다. 이는 transport 호출 수를 줄이는 최적화이며 signal 의미와 문법을 바꾸지 않습니다.

첨부파일이 있는 경우 `SELF_RUN_REFERENCE_FOLDER_ID`도 같은 `DRIVE_JOB_FOLDER_ID`를 사용합니다. 사용자가 앱에 입력한 원본 요구사항은 `[요구사항]` 행 바로 뒤에 trim·요약 없이 그대로 붙습니다.

`SELF_RUN_SKILL_DOCUMENT_ID`는 앱의 단일 상수에서 prompt metadata로만 전달합니다. 앱은 해당 Google Drive 문서의 이름 검색, 다운로드, 캐싱, 버전 판정, 규칙 파싱 또는 Project 규범과의 우선순위 해석을 수행하지 않습니다. 문서 로드와 Project 규범 병행 적용은 ChatGPT가 담당합니다.

CONTINUE에는 global Skill ID나 Project metadata를 반복 삽입하지 않으며, 타임스탬프와 `SELF_RUN_CONTINUE` 제어행만 보냅니다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_CONTINUE <RUN_ID>]
```

유효한 NEXT_INPUT이 있으면 앱은 strict Base64URL/UTF-8 검증 후 위 제어행 뒤에 개행 하나와 decoded 원문을 정확히 한 번 추가합니다. payload가 없으면 제어행만 보냅니다. padding, unknown/duplicate field, invalid UTF-8, encoded 900,000 characters 또는 decoded 675,000 bytes 초과는 protocol error로 fail closed하며 plain CONTINUE로 강등하지 않습니다.

## Run 폴더 signal document

신규 transport에서 ChatGPT → Android 상태신호는 실행턴 문서 append가 아니라 Run 작업폴더 아래에 생성되는 독립 Google Doc 한 개입니다. 활성 신호 의미는 기존과 동일하게 `SELF_RUN_TURN_COMPLETED`, `SELF_RUN_USER_ACTION_REQUIRED`, `SELF_RUN_PAUSED`, `SELF_RUN_DONE`입니다.

### 제목 계약

NEXT_INPUT이 없는 문서는 제목 자체가 기존 canonical signal 한 줄과 정확히 같습니다.

CHAT 예시:

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID>]
```

WORK 예시:

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> MODEL=<sol|terra|luna> REASONING=<high|xhigh|max|ultra>]
```

WORK의 MODEL·REASONING은 기존과 동일하게 다음 턴 profile입니다. recovery completion은 기존 field 순서를 그대로 유지합니다. USER_ACTION_REQUIRED, PAUSED, DONE도 기존 bare canonical signal을 제목으로 사용합니다.

NEXT_INPUT이 있는 경우 제목에는 실제 Base64URL 값을 쓰지 않고 그 위치에 정확히 다음 marker만 기록합니다.

```text
NEXT_INPUT_B64URL=BODY
```

예:

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> NEXT_INPUT_B64URL=BODY]
```

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> MODEL=sol REASONING=xhigh NEXT_INPUT_B64URL=BODY]
```

제목에 실제 NEXT_INPUT payload를 직접 넣은 문서는 신규 transport의 canonical signal document가 아닙니다.

### NEXT_INPUT 본문 계약

`NEXT_INPUT_B64URL=BODY` marker가 있는 문서만 앱이 Google Docs 본문을 엽니다. 본문은 trailing newline을 제외하고 정확히 한 줄이어야 합니다.

```text
NEXT_INPUT_B64URL=<canonical URL-safe Base64 without padding>
```

추가 문장, 공백 field, padding(`=`), 다중 행 또는 다른 field가 있으면 fail closed합니다. 앱은 제목 marker를 검증된 본문 field로 치환해 기존 canonical signal 한 줄을 재구성한 뒤 기존 `DriveSignalParser`에 전달합니다.

marker가 없는 signal document의 본문은 signal 판정에 사용하지 않으며 읽지 않습니다.

### 최신 signal 판정

앱은 정확한 `DRIVE_JOB_FOLDER_ID`의 직접 자식 중 native Google Doc이면서 현재 RUN_ID에 맞는 canonical signal title만 후보로 사용합니다. 다른 폴더, 다른 RUN_ID, 휴지통 항목, shared 항목, 비정상 timestamp·title은 무시합니다.

후보는 오름차순으로 다음 키를 사용해 정렬합니다.

1. Drive provider `createdTime`
2. 제목의 `yyyy.mm.dd | hh:mm:ss` timestamp
3. Drive `fileId`

따라서 최신 항목은 위 정렬의 마지막 문서입니다. `fileId`는 제목이 같은 복수 문서의 안정적인 identity와 replay 방지에 사용합니다. Android 내부에는 이 정렬된 문서들을 기존 append-only signal line처럼 합성하여 기존 physical cursor semantics를 유지합니다.

## Android 진행 기준

앱은 composer의 정확한 전문 readback과 활성 SEND를 확인한 뒤 명령을 클릭합니다. 제출 클릭과 같은 JavaScript 실행에서 STOP/SEND 영역에 `MutationObserver`를 설치하고, 클릭 결과를 곧바로 완료 관찰 상태로 전이합니다. 전송 이후 별도 STOP/SEND polling으로 제출을 재확인하지 않습니다. STOP을 처음 관찰하면 현재 run/token 범위의 내구 증거를 기록합니다. 그 증거가 있는 경우에만 WebView·renderer 복구 뒤의 idle baseline을 허용합니다. STOP을 본 뒤 완료 상태가 나타나면 5초를 기다리고 버튼 상태를 한 번 더 확인하며, 여전히 완료 상태일 때만 native 완료 콜백을 보냅니다. STOP이 돌아오면 타이머를 취소하고 관찰을 계속합니다.

native 완료 콜백 직후 앱은 실행턴 문서 본문 변경을 기다리지 않고 Run 폴더의 signal document metadata를 즉시 조회합니다. 마지막으로 소비한 signal cursor 이후 `TURN_COMPLETED`가 있으면 NEXT_INPUT과 Work profile을 적용합니다. 없으면 5초 간격으로 최대 3분 재확인합니다. `createdTime`과 제목 timestamp는 최신 signal 선택에 사용하고, 기존 실행턴 문서 `modifiedTime`은 legacy 호환 경로 외에는 신규 signal 상태가 아닙니다.

3분이 지나도 `TURN_COMPLETED`를 찾지 못한 경우, signal-document transport를 사용하는 현재 Run에서 문서 누락 복구를 아직 사용하지 않았다면 앱은 자동 승계 전에 정확히 한 번 `[SELF_RUN_TURN_DOCUMENT_RETRY <RUN_ID>]`를 같은 conversation에 제출합니다. 이 제어신호는 작업 진행용 CONTINUE가 아니며, ChatGPT는 canonical SelfRun 운영문서에 따라 직전 정상 턴에서 누락된 기존 `SELF_RUN_TURN_COMPLETED` signal document만 다시 생성합니다. 복구 요청이 제출되기 전 `TURN_DOCUMENT_RETRY`가 PENDING인 동안 동일한 `TURN_COMPLETION_SIGNAL_TIMEOUT`이 다시 들어오면 같은 Run의 재생성 요청 상태를 멱등하게 유지하고 successor 자동 승계로 넘어가지 않습니다. 복구 요청 제출이 확인되면 PENDING을 해제하고 앱은 다시 답변 완료와 Drive signal을 관찰하며, 같은 Run에서 두 번째로 3분 누락이 발생하면 복구신호를 반복하지 않고 기존 `TURN_COMPLETION_SIGNAL_TIMEOUT` 자동승계 경로를 사용합니다. 자동승계 successor는 새로운 RUN_ID이므로 문서 누락 복구 횟수를 predecessor에서 상속하지 않고 새 1회 기회를 가집니다. 복구 제어신호 자체는 NEXT_INPUT을 소비하거나 덧붙이지 않으며, 보류 중인 사용자 NEXT_INPUT은 다음 정상 CONTINUE까지 보존합니다.

제출 성공과 답변 완료는 내부 WebView DOM으로 확정하며 Drive의 별도 수신확인 신호를 기다리거나 재제출 게이트로 사용하지 않습니다. STOP/SEND 완료 판정에 짧은 주기의 반복 polling을 사용하지 않습니다. 입력 전문 readback 뒤 SEND가 활성화된 경우에만 다음 프롬프트를 클릭합니다.

## Runs folder binding과 OAuth

canonical 사용자 안내 경로는 `/GPT/Self Run/Runs/`입니다. 기존에 저장된 Runs folder ID는 경로 문자열이 아니라 Drive 객체 ID이므로 앱이 먼저 해당 ID를 검증하여 계속 사용합니다. 폴더 이동만으로 재연결을 요구하지 않으며, 기존 ID가 없거나 실제 접근·쓰기 권한 검증에 실패한 경우에만 Google Picker 재연결 흐름을 사용합니다.

OAuth는 다음 최소 조합을 사용합니다.

- `https://www.googleapis.com/auth/drive.file`: Picker로 승인된 Runs 폴더와 앱이 생성한 객체의 쓰기·관리
- `https://www.googleapis.com/auth/drive.metadata.readonly`: ChatGPT가 생성한 signal document의 Run 폴더 내 metadata 검색
- `https://www.googleapis.com/auth/documents.readonly`: `NEXT_INPUT_B64URL=BODY`인 signal document의 본문 읽기

앱은 전체 Drive 쓰기 scope인 `drive`를 요청하지 않습니다. read-only scope는 ChatGPT가 앱과 다른 OAuth client context에서 생성한 signal document를 발견하고 필요한 경우 본문을 읽기 위한 것입니다. signal 후보는 반드시 현재 Run의 정확한 job folder와 RUN_ID/title 문법으로 다시 제한합니다.

## WebView 책임

Drive V1의 WebView 책임은 canonical conversation의 composer 제어권 확보, 명령 제출, STOP/SEND 영역의 완료 상태 감지입니다. assistant 메시지 본문이나 SELF_RUN 제어문구는 완료 판정에 사용하지 않습니다. 입력창을 찾지 못하거나 renderer/WebView가 소실되면 저장된 conversation URL을 다시 열어 composer를 재획득합니다. 복구 가능한 WebView·네트워크 오류를 Job 종료 사유로 승격하지 않습니다.

WORK 모드의 다음 모델·추론 설정은 DOM에서 assistant 제어문구를 읽어 결정하지 않습니다. Drive signal document title의 MODEL·REASONING을 권위 원본으로 사용합니다.

## 일시정지와 재개

USER_ACTION_REQUIRED와 PAUSED는 Job 종료가 아닌 보존형 pause입니다. 사용자가 앱에서 재개를 누르면 `RESUME_BASELINE` 경로로 현재 Run 폴더의 signal document 목록을 다시 읽습니다. pause 이후 새로 생성된 최신 completion에 유효한 NEXT_INPUT이 있으면 그 원문만 기존 CONTINUE 뒤에 붙이고, 없으면 existing plain CONTINUE를 그대로 제출합니다. 별도 pause-origin/anchor/fingerprint 상태머신은 추가하지 않습니다.

## signal create/readback 실패

ChatGPT는 신규 signal을 기록할 때 `DRIVE_JOB_FOLDER_ID`가 가리키는 정확한 폴더 바로 아래에 native Google Doc을 생성하고 제목을 canonical signal로 지정합니다. NEXT_INPUT이 있을 때만 본문에 Base64URL field를 기록합니다.

생성 또는 본문 쓰기 결과가 실패·timeout·불명확하면 같은 폴더를 먼저 재조회하여 동일 logical signal 문서가 이미 생성되었는지 확인합니다. 생성 성공 후에는 최소한 fileId, title, MIME type, parent folder와 필요한 NEXT_INPUT 본문을 readback하여 의도한 signal과 일치하는지 확인합니다. 불명확한 상태에서 같은 signal 문서를 무조건 추가 생성하지 않습니다.

Android 앱은 malformed signal document를 의미 추정으로 복구하지 않습니다. canonical title 또는 필요한 본문 검증이 실패하면 해당 항목을 정상 signal로 소비하지 않습니다.

현재 `1.6.1-dev4`처럼 bootstrap에 `DRIVE_JOB_FOLDER_ID`가 없었던 기존 Run은 해당 클라이언트가 종료될 때까지 legacy turn-document signal 경로를 사용할 수 있습니다. `1.6.1-dev5` 이후 신규 Run은 job-folder signal document transport를 사용합니다.

앱 내부 WebView host, Foreground Service, WakeLock, polling timer, backoff, 로컬 상태와 서명 구현은 [SELF_RUN_DRIVE_RUNTIME](SELF_RUN_DRIVE_RUNTIME.md)에 분리합니다.
