# SelfRun orchestration: legacy WebView + Drive V1

이 문서는 Drive V1의 실행 차이만 규정합니다. 작업 해석, HANDOFF, NEXT/DONE/PAUSE/USER_ACTION_REQUIRED 의미와 역할 전환은 공통 `SELF_RUN_ORCHESTRATION_SKILL`을 따릅니다.

## Drive V1 판별

bootstrap에는 아래 두 식별 필드만 추가합니다.

```text
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_TURN_DOCUMENT_ID=<documentId>
```

## 단일행 signal event

Drive 작업문서는 append-only 실행 확인 채널입니다. 이벤트는 다음 형식 한 줄만 사용합니다.

```text
[yyyy.mm.dd | hh:mm:ss] [<SIGNAL> <RUN_ID>]
```

허용 signal은 `SELF_RUN_COMMAND_RECEIVED`, `SELF_RUN_TURN_COMPLETED`, `SELF_RUN_USER_ACTION_REQUIRED`, `SELF_RUN_PAUSED`, `SELF_RUN_DONE`입니다. timestamp에는 `KST`, UTC offset 같은 추가 문자열을 넣지 않습니다.

ChatGPT가 bootstrap 또는 CONTINUE를 실제 수신하면 실질 작업 전에 `SELF_RUN_COMMAND_RECEIVED`를 기록하고 같은 document ID를 readback합니다. 턴을 계속할 때는 최종 답변 직전에 `SELF_RUN_TURN_COMPLETED`를 기록하고 readback합니다. 사용자 조치, 명시적 pause, 전체 완료도 각각 대응 signal을 같은 방식으로 기록합니다.

## Android 진행 기준

앱은 명령 클릭 직후 user-message DOM, assistant streaming, stop button 또는 completion DOM을 기다리지 않고 Drive polling으로 복귀합니다. 마지막으로 소비한 실제 SelfRun signal의 cursor를 영속하고 이후 추가된 signal만 새 이벤트로 처리합니다. modifiedTime은 읽기 최적화 힌트일 뿐 상태신호가 아닙니다.

`SELF_RUN_COMMAND_RECEIVED`를 별도로 보지 못했더라도 같은 polling read에서 TURN_COMPLETED 또는 더 진행된 특수 signal이 새로 확인되면 제출은 정상 수신된 것으로 처리합니다.

명령 제출 뒤 새 ACK 또는 더 진행된 signal이 없으면 5분 뒤 같은 의미의 bootstrap/CONTINUE를 다시 제출합니다. retry 횟수나 누적 시간을 terminal 조건으로 사용하지 않습니다.

TURN_COMPLETED를 소비하면 30초∼1분의 단순 UI 안전 guard를 거친 뒤 같은 conversation에 CONTINUE를 제출하고 즉시 Drive polling으로 복귀합니다. 현재 구현 기본 guard는 45초입니다.

## WebView 책임

Drive V1의 WebView 책임은 assistant 완료 감시가 아니라 canonical conversation의 composer 제어권 확보와 명령 제출입니다. 입력창을 찾지 못하거나 renderer/WebView가 소실되면 저장된 conversation URL을 다시 열어 composer를 재획득합니다. 복구 가능한 WebView·네트워크 오류를 Job 종료 사유로 승격하지 않습니다.

WORK 모드에서는 TURN_COMPLETED 뒤 최신 assistant의 SELF_RUN_NEXT를 한 번 best-effort로 읽어 role/model/reasoning을 적용할 수 있습니다. 이 read는 completion 판정이 아니며 읽지 못해도 현재 안전한 설정으로 CONTINUE를 진행합니다.

## 일시정지와 재개

USER_ACTION_REQUIRED와 PAUSED는 Job 종료가 아닌 보존형 pause입니다. 사용자가 앱에서 재개를 누르면 현재 작업문서의 마지막 실제 signal 위치만 baseline으로 저장한 뒤 최신 signal 종류와 관계없이 CONTINUE를 강제 제출합니다. 이후 ACK가 없으면 동일한 5분 무제한 재제출 규칙을 적용합니다.

## signal write/readback 실패

signal append 결과가 실패·타임아웃·불명확하면 같은 문서를 먼저 재조회합니다. 의도한 행이 이미 있으면 중복 append하지 않고 readback 검증을 계속합니다. 행이 없는 것이 확인된 경우에만 같은 논리 signal을 다시 append합니다. readback만 실패한 경우에는 새 행을 추가하지 않고 readback을 재시도합니다. signal 기록과 readback이 성공한 뒤에만 해당 턴의 최종 답변을 출력합니다.
