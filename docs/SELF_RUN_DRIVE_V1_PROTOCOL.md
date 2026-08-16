# SelfRun Drive V1 protocol

이 문서는 Android 앱 구현과 Drive V1 외부 인터페이스 계약을 설명합니다. SelfRun의 AI 역할 전환, HANDOFF, NEXT/DONE/PAUSE/USER_ACTION_REQUIRED 의미와 완료 판정은 Google Drive의 canonical `SELF_RUN_ORCHESTRATION_SKILL` 문서가 소유합니다.

## Global SelfRun bootstrap

SelfRun Drive는 모든 대화에서 같은 bootstrap을 사용합니다. 앱은 현재 ChatGPT 대화가 Project인지 판정하지 않으며 Project 이름·ID·SKILL 위치를 탐색하지 않습니다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_BOOTSTRAP 0.2.0 <RUN_ID> MODE=<CHAT|WORK>]
SELF_RUN_CLIENT=DRIVE_V1
SELF_RUN_SKILL_DOCUMENT_ID=1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs
DRIVE_TURN_DOCUMENT_ID=<documentId>

이 실행은 SelfRun이다.

실질 작업을 시작하기 전에 위 SelfRun 운영문서 ID가 가리키는 Google Drive 문서의 현재 최신 메타데이터와 전체 내용을 읽고 SelfRun 실행 규범으로 적용한다.

현재 conversation이 ChatGPT Project 내부의 대화라면 해당 Project의 프로젝트 지침과 그 지침이 지정하는 SKILL·운영문서도 함께 적용한다. 프로젝트의 업무·도메인·데이터·산출물·프로젝트 고유 운영 규칙은 해당 Project 규범을 따른다.

DRIVE_TURN_DOCUMENT_ID 문서에 Command Received 신호 입력 후 아래 요구사항을 수행할 것.

[요구사항]
<사용자가 앱에 입력한 원본 요구사항>
```

`SELF_RUN_SKILL_DOCUMENT_ID`는 앱의 단일 상수에서 prompt metadata로만 전달합니다. 앱은 해당 Google Drive 문서의 이름 검색, 다운로드, 캐싱, 버전 판정, 규칙 파싱 또는 Project 규범과의 우선순위 해석을 수행하지 않습니다. 문서 로드와 Project 규범 병행 적용은 ChatGPT가 담당합니다.

bootstrap은 `[요구사항]` 직전에 `DRIVE_TURN_DOCUMENT_ID 문서에 Command Received 신호 입력 후 아래 요구사항을 수행할 것.`을 정확히 한 번 넣어, 요구사항 실행보다 실행턴 문서의 Command Received 기록을 먼저 수행하도록 명시합니다.

사용자가 앱에 입력한 원본 요구사항은 `[요구사항]` 행 바로 뒤에 trim·요약 없이 그대로 붙습니다. bootstrap에는 canonical SelfRun 운영문서가 소유하는 역할 전환·HANDOFF·continuation·제어신호·완료 판정 의미를 다시 설명하는 중복 문장을 넣지 않습니다.

CONTINUE에는 global Skill ID나 Project metadata를 반복 삽입하지 않으며, ChatGPT가 실행턴 문서에 `SELF_RUN_COMMAND_RECEIVED`를 기록해야 함을 명확히 알리는 고정 문구를 두 번째 행에 붙입니다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_CONTINUE <RUN_ID>]
Command Recevied Record Required
```

`Command Recevied Record Required`는 현재 앱 외부 프롬프트 계약의 고정 문자열이며 철자를 임의로 교정하지 않습니다.

protocol 0.2.0에서 현재 적용 대상 `SELF_RUN_TURN_COMPLETED`에 유효한 `NEXT_INPUT_B64URL`이 있으면 앱은 strict Base64URL/UTF-8 검증 후 위 두 줄 뒤에 개행 하나와 decoded 원문을 정확히 한 번 추가합니다. payload가 없으면 기존 두 줄은 byte-for-byte 동일합니다. padding, unknown/duplicate field, invalid UTF-8, encoded 900,000 characters 또는 decoded 675,000 bytes 초과는 protocol error로 fail closed하며 plain CONTINUE로 강등하지 않습니다.

## Drive 실행문서 signal

Drive 작업문서는 append-only 실행 확인 채널입니다. `SELF_RUN_COMMAND_RECEIVED`, `SELF_RUN_USER_ACTION_REQUIRED`, `SELF_RUN_PAUSED`, `SELF_RUN_DONE`은 계속 bare 한 줄 형식만 사용합니다. protocol 0.2.0의 CHAT `SELF_RUN_TURN_COMPLETED`는 bare 형식 또는 `NEXT_INPUT_B64URL=<VALUE>` 한 필드를 가질 수 있고, WORK completion은 기존 `MODEL`·`REASONING` 뒤에 optional `NEXT_INPUT_B64URL`을 마지막 field로 가질 수 있습니다. timestamp에는 `KST`, UTC offset 같은 추가 문자열을 넣지 않습니다.

ChatGPT가 bootstrap 또는 CONTINUE를 실제 수신하면 실질 작업 전에 `SELF_RUN_COMMAND_RECEIVED`를 기록하고 같은 document ID를 readback합니다. 턴을 계속할 때는 최종 답변 직전에 `SELF_RUN_TURN_COMPLETED`를 기록하고 readback합니다. 사용자 조치, 명시적 pause, 전체 완료도 각각 대응 signal을 같은 방식으로 기록합니다.

## Android 진행 기준

앱은 명령 클릭 직후 user-message DOM, assistant streaming, stop button 또는 completion DOM을 기다리지 않고 Drive polling으로 복귀합니다. 마지막으로 소비한 실제 SelfRun signal의 cursor를 영속하고 이후 추가된 signal만 새 이벤트로 처리합니다. `modifiedTime`은 읽기 최적화 힌트일 뿐 상태신호가 아닙니다.

`SELF_RUN_COMMAND_RECEIVED`를 별도로 보지 못했더라도 같은 polling read에서 TURN_COMPLETED 또는 더 진행된 특수 signal이 새로 확인되면 제출은 정상 수신된 것으로 처리합니다. 명령 제출 뒤 새 ACK 또는 더 진행된 signal이 없으면 5분 뒤 같은 의미의 bootstrap/CONTINUE를 다시 제출하며 retry 횟수나 누적 시간을 terminal 조건으로 사용하지 않습니다.

TURN_COMPLETED를 소비하면 30초∼1분의 단순 UI 안전 guard를 거친 뒤 같은 conversation에 CONTINUE를 제출하고 즉시 Drive polling으로 복귀합니다. 현재 구현 기본 guard는 45초입니다.

## Runs folder binding

canonical 사용자 안내 경로는 `/GPT/Self Run/Runs/`입니다. 기존에 저장된 Runs folder ID는 경로 문자열이 아니라 Drive 객체 ID이므로 앱이 먼저 해당 ID를 `drive.file` 권한으로 검증하여 계속 사용합니다. 폴더 이동만으로 재연결을 요구하지 않으며, 기존 ID가 없거나 실제 접근·쓰기 권한 검증에 실패한 경우에만 Google Picker 재연결 흐름을 사용합니다.

OAuth scope는 `https://www.googleapis.com/auth/drive.file` 하나를 유지합니다. canonical SelfRun 운영문서를 앱이 직접 읽기 위해 `drive` 또는 `drive.readonly`로 확대하지 않습니다.

## WebView 책임

Drive V1의 WebView 책임은 assistant 완료 감시가 아니라 canonical conversation의 composer 제어권 확보와 명령 제출입니다. 입력창을 찾지 못하거나 renderer/WebView가 소실되면 저장된 conversation URL을 다시 열어 composer를 재획득합니다. 복구 가능한 WebView·네트워크 오류를 Job 종료 사유로 승격하지 않습니다.

WORK 모드에서는 TURN_COMPLETED 뒤 최신 assistant의 SELF_RUN_NEXT를 한 번 best-effort로 읽어 role/model/reasoning을 적용할 수 있습니다. 이 read는 completion 판정이 아니며 읽지 못해도 현재 안전한 설정으로 CONTINUE를 진행합니다.

## 일시정지와 재개

USER_ACTION_REQUIRED와 PAUSED는 Job 종료가 아닌 보존형 pause입니다. 사용자가 앱에서 재개를 누르면 1.2.1의 기존 `RESUME_BASELINE` 경로로 현재 작업문서를 먼저 다시 읽습니다. pause 이후 새로 추가된 최신 completion에 유효한 NEXT_INPUT이 있으면 그 원문만 기존 CONTINUE 뒤에 붙이고, 없으면 기존 plain CONTINUE를 그대로 제출합니다. 별도 pause-origin/anchor/fingerprint 상태머신은 추가하지 않습니다. 이후 ACK가 없으면 기존 5분 무제한 재제출 규칙을 적용합니다.

## signal write/readback 실패

signal append 결과가 실패·타임아웃·불명확하면 같은 문서를 먼저 재조회합니다. 의도한 행이 이미 있으면 중복 append하지 않고 readback 검증을 계속합니다. 행이 없는 것이 확인된 경우에만 같은 논리 signal을 다시 append합니다. readback만 실패한 경우에는 새 행을 추가하지 않고 readback을 재시도합니다. signal 기록과 readback이 성공한 뒤에만 해당 턴의 최종 답변을 출력합니다.

앱 내부 WebView host, Foreground Service, WakeLock, polling timer, backoff, 로컬 상태와 서명 구현은 [SELF_RUN_DRIVE_RUNTIME](SELF_RUN_DRIVE_RUNTIME.md)에 분리합니다.
