# SelfRun orchestration: legacy WebView + Drive V1

이 문서는 Drive V1 차이 규격이다. SelfRun의 작업 해석, HANDOFF, NEXT/DONE/PAUSE/USER_ACTION_REQUIRED 의미와 역할 전환은 기존 공통 SelfRun 운영 규칙을 그대로 사용한다. Drive 버전용 SelfRun 규칙 전체를 복제하지 않는다.

## Drive V1 판별

ChatGPT에 전달되는 bootstrap에서 다음 두 정보만으로 Drive V1을 판별한다.

```text
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_TURN_DOCUMENT_ID=<documentId>
```

Android application ID, Runs 기준 폴더 ID, Job 폴더 ID, 문서 URL, Drive protocol version, expected turn과 앱 내부 상태관리 값은 ChatGPT bootstrap에 전달하지 않는다. 이 값들은 필요한 경우 Android 앱 내부 Drive API 상태로만 유지한다.

실제 첫 프롬프트 형식은 다음과 같다.

```text
[SELF_RUN_BOOTSTRAP 0.1.0 <RUN_ID> MODE=<MODE>]
SELF_RUN_CLIENT=DRIVE_V1
DRIVE_TURN_DOCUMENT_ID=<documentId>
최종 답변 출력 직전에 위 작업문서에 이 턴의 SelfRun 완료 신호와 완료 시점을 기록한다.

<사용자의 실제 작업 지시>
```

## Drive V1의 유일한 실행 차이

기존 WebView SelfRun은 assistant 답변 완료 여부를 WebView에서 판정한다. Drive V1은 그 완료 판정만 Google Drive 작업문서의 새 완료 기록으로 대체한다.

현재 턴의 실제 작업과 검증을 마친 뒤 최종 답변을 출력하기 직전에 지정된 `DRIVE_TURN_DOCUMENT_ID` 문서에 해당 턴의 SelfRun 완료 신호와 완료 시점을 기록하고 readback한다. 그 밖의 SelfRun 제어 의미는 공통 운영 규칙을 따른다.

Android 쪽 정상 루프는 다음과 같다.

```text
프롬프트 제출
→ 제출 성공만 확인
→ Drive 작업문서 업데이트 대기
→ 새 완료 기록 수락
→ 45초 UI 안정 대기
→ 같은 conversation 입력창 확보
→ [SELF_RUN_CONTINUE <RUN_ID>] 강제 입력·제출
→ 제출 성공만 확인
→ Drive 작업문서 업데이트 대기
```

45초 지연은 assistant completion을 재확인하기 위한 시간이 아니다. Drive 완료 기록이 authoritative completion signal이다. Drive 완료 기록 이후 stop 버튼, streaming, assistant message completion, generation 상태를 다음 제출 조건으로 사용하지 않는다.

## 제출 성공과 중복 방지

continuation은 클릭 전에 해당 conversation에서 동일한 CONTINUE 사용자 턴 수를 Android 영속 상태와 WebView marker에 baseline으로 저장한다. 제출 성공은 baseline 이후 동일 사용자 턴 수가 실제 증가했는지 확인한다. assistant DOM은 확인하지 않는다.

결과가 즉시 명확하지 않으면 같은 신호를 바로 다시 보내지 않는다. 5분 대기 상태를 영속하고, 5분 뒤 먼저 기존 제출이 늦게 성공했는지 같은 baseline으로 확인한다. 이미 성공했으면 재전송하지 않고 Drive 대기로 복귀한다. 아직 미제출이면 입력창을 다시 확보하고 동일 신호를 다시 준비·제출한다.

제출 실패·미확인은 terminal error가 아니다. 5분 재시도 횟수에 상한을 두지 않는다. 시도 횟수는 관찰용으로만 기록하며 종료 조건으로 사용하지 않는다.

## WebView 제어권 복구

Drive V1에서 WebView가 필요한 이유는 assistant completion 감시가 아니라 동일 conversation의 프롬프트 입력·제출 제어권 확보이다. 기존 WebView가 유효하면 그대로 쓰고, 입력창을 찾지 못하거나 renderer/WebView가 소실되면 저장된 conversation URL을 다시 열어 입력창을 재획득한다. 네트워크·WebView의 복구 가능한 오류는 Job 종료 사유로 승격하지 않는다.

## 일시정지와 재개

`[SELF_RUN_PAUSE ...]`, `[SELF_RUN_USER_ACTION_REQUIRED ...]`, 사용자 수동 일시정지는 Job 종료가 아닌 일시정지다. 일시정지 동안 45초 continuation 예약과 5분 제출 재시도 timer는 실행하지 않지만 pending Drive event, 제출 baseline, retry 종류·예정 시각·시도 수와 conversation/document 식별자는 보존한다.

재개 시 기존 WebView 또는 저장된 conversation URL로 입력 제어권을 확보한다. pending 제출이 있으면 먼저 기존 성공 여부를 확인하고 필요할 때만 동일 신호를 제출한다. 재개 후 제출 실패도 다시 5분 재시도 상태로 돌아간다.

## Drive 완료 기록 형식

ChatGPT 실행 측이 최종 답변 직전에 쓰는 완료 기록 형식과 SIGNAL/HANDOFF 문법은 공식 `SELF_RUN_ORCHESTRATION_SKILL`을 따른다. Android 앱은 완전한 새 기록만 수락하고 이미 소비한 event sequence/turn을 다시 소비하지 않는다. `DONE`은 정상 종료이며 `PAUSE`와 `USER_ACTION_REQUIRED`는 자동 continuation을 보내지 않고 일시정지한다.

## 생성 단계

Job 폴더와 작업문서의 생성·Drive 인증·parent 검증·초기 readback은 Android 앱의 기존 Drive setup 책임이다. 이번 dev3 턴 진행 변경은 해당 생성 구조를 재설계하지 않는다.
