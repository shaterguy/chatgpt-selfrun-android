# SELF_RUN_ORCHESTRATION_SKILL 0.4.0 canonical patch proposal

이 문서는 canonical Google Drive 운영문서 `SELF_RUN_ORCHESTRATION_SKILL`의 직접 수정 권한이 없는 Vibe Coding 프로젝트에서 생성한 완성 수정안이다. 적용 대상은 현재 0.3.6이며, 적용 후 `version: 0.4.0`, `skill-version: 0.4.0`, `protocol-version: 0.2.0`으로 맞춘다. 0.1.0 client에는 아래 0.2.0 확장 `NEXT_INPUT_B64URL`을 emit하지 않는다.

## Frontmatter replacement

```text
version: 0.4.0
skill-version: 0.4.0
protocol-version: 0.2.0
last-updated: 2026-08-16
```

## 4.2 사용자 조치 필요 판정 — replacement

`SELF_RUN_USER_ACTION_REQUIRED`는 SelfRun이 다음 사용자-role 입력을 authoritative source만으로 확정할 수 없을 때만 사용한다. 이미 사용자가 선택·승인·지시한 내용, 프로젝트 규범이나 현재 실행계획이 요구하는 확정 입력, 직전 대화에서 이미 결정된 내용, 형식적 확인처럼 다음 문구를 정확히 재구성할 수 있는 경우에는 사용자 조치가 아니다.

예를 들어 `승인할게`, `계속해`, `진행해`, `원격 push를 진행해`처럼 현재 실행 상태에서 의미와 문구가 결정되어 있다면 AI는 pause하지 않는다. 해당 턴의 실질 작업을 완료한 뒤 Drive 실행턴 문서에 `SELF_RUN_TURN_COMPLETED`와 optional `NEXT_INPUT_B64URL`을 기록·readback하고 정상 continuation으로 넘긴다.

사용자 조치가 필요한 경우는 다음 중 하나다.

1. 아직 결정되지 않은 선택지를 사용자가 새로 골라야 한다.
2. AI가 보유하지 않은 사용자 고유 정보가 새로 필요하다.
3. 로그인, OAuth, 앱·OS 조작, 기기 조작, 파일 업로드 등 대화 밖에서 사용자만 수행할 수 있는 수동 행동이 필요하다.

1·2의 선택형 사용자 조치에서는 `SELF_RUN_USER_ACTION_REQUIRED`를 기록한 뒤 pause latch를 유지한다. 사용자가 자연어 답변을 보냈다는 사실 자체는 latch 해제가 아니다. 그 사용자 답변을 받은 assistant 턴은 resume-preparation 턴이며, 선택을 해석해 다음 user-role에 들어갈 정확한 입력을 확정하고 `SELF_RUN_TURN_COMPLETED ... NEXT_INPUT_B64URL=<VALUE>`를 기록·readback한 뒤 사용자에게 앱의 재개 버튼을 누르도록 안내한다. 이 resume-preparation 턴에서 다음 실질 작업까지 실행하지 않는다.

3의 대화 밖 수동 행동은 완료 후 별도 resume-preparation completion이 없어도 앱이 post-anchor Drive 상태를 확인한 뒤 plain CONTINUE를 사용할 수 있다.

## 4.3 일시정지와 latch — replacement

`SELF_RUN_USER_ACTION_REQUIRED`와 `SELF_RUN_PAUSED`는 terminal이 아니라 durable pause다. AI가 만든 USER_ACTION_REQUIRED/PAUSED latch는 일반 사용자 메시지만으로 해제하지 않는다. UI 수동 일시정지는 AI pause와 구분한다.

pause 시 앱은 최소한 다음 anchor를 durable state로 저장한다.

- RUN_ID
- origin: `AI_USER_ACTION_REQUIRED`, `AI_PAUSED`, `UI_MANUAL`, `EXTERNAL_MANUAL`
- cause
- `pausedFromPhase`
- Drive signal cursor 또는 동일한 의미의 stable event identity
- Drive version 및 modifiedTime 등 재조정에 필요한 문서 identity
- pause를 발생시킨 signal identity

재개 버튼은 이 anchor 이후의 Drive signal만 대상으로 reconciliation한다. 단순히 현재 문서의 latest completion을 baseline으로 삼거나 과거 completion을 재사용하지 않는다. anchor가 불명확하면 stale completion을 추정하지 말고 재조정하거나 재개를 차단한다.

## 5.2 TURN_COMPLETED grammar — replacement

protocol 0.2.0의 Drive completion grammar는 다음 네 가지다.

```text
# CHAT bare
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID>]

# CHAT + NEXT_INPUT
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> NEXT_INPUT_B64URL=<VALUE>]

# WORK bare
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> MODEL=<MODEL> REASONING=<REASONING>]

# WORK + NEXT_INPUT
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_TURN_COMPLETED <RUN_ID> MODEL=<MODEL> REASONING=<REASONING> NEXT_INPUT_B64URL=<VALUE>]
```

`NEXT_INPUT_B64URL`은 다음 user-role 입력의 UTF-8 원문을 URL-safe Base64, no padding, no wrap으로 encoding한 값이다. field 순서는 위 grammar를 따른다. unknown field, duplicate field, non-canonical padding, decode 실패, invalid UTF-8, 허용 크기 초과는 protocol error다. invalid NEXT_INPUT을 무시하고 bare continuation으로 강등해서는 안 된다.

WORK의 MODEL/REASONING은 계속 Drive가 authoritative source다. invalid WORK profile이면 기존 `SELF_RUN_TURN_INFO_REWRITE <RUN_ID>` 외부 문자열을 그대로 사용한다. rewrite용 completion은 기존 유효 NEXT_INPUT을 잃지 않고 보존한다.

`NEXT_INPUT_B64URL`은 pause/resume 전용 field가 아니다. 정상 자동 continuation에서도 다음 user-role 입력이 authoritative하게 결정되면 사용할 수 있다.

## 5.3 CONTINUE와 COMMAND_RECEIVED — replacement

기존 bare CONTINUE는 그대로 유지한다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_CONTINUE <RUN_ID>]
Command Recevied Record Required
```

철자 `Command Recevied Record Required`를 포함한 위 두 줄은 기존 외부 계약이며 수정하지 않는다.

NEXT_INPUT이 있는 경우 위 두 줄 뒤에 decoded UTF-8 원문을 변경 없이 추가한다.

```text
[yyyy.mm.dd | hh:mm:ss] [SELF_RUN_CONTINUE <RUN_ID>]
Command Recevied Record Required
<NEXT_INPUT 원문>
```

CONTINUE+optional NEXT_INPUT을 받은 assistant는 payload를 해석하거나 실질 작업을 수행하기 전에 먼저 동일 실행턴 문서에 `SELF_RUN_COMMAND_RECEIVED`를 기록하고 readback한다. `COMMAND_RECEIVED` 계약은 optional payload 도입으로 약화되지 않는다.

앱은 동일 logical continuation을 retry하거나 process restart로 복구할 때 최대 한 번만 user-role 메시지를 제출해야 한다. identity에는 최소 RUN_ID, Drive completion/event identity, pause anchor identity가 있으면 그것, NEXT_INPUT fingerprint, durable command marker가 포함되어야 하며 기존 ACK/retry marker 체계를 재사용한다.

## 6. 재개 버튼과 Drive reconciliation — replacement

재개 버튼은 더 이상 무조건 `PHASE_SEND_CONTINUE`로 이동하는 명령이 아니다. 버튼 입력 후 앱은 우선 실행턴 문서를 다시 읽고 pause anchor 이후 signal을 수집·검증한 뒤 다음 규칙을 적용한다.

1. post-anchor `DONE`: CONTINUE 없이 종료한다.
2. 더 최신 `USER_ACTION_REQUIRED` 또는 `PAUSED`: pause를 유지하고 CONTINUE를 보내지 않는다.
3. `TURN_COMPLETED` + valid NEXT_INPUT: CHAT에서는 payload를, WORK에서는 Drive MODEL/REASONING과 payload를 적용해 정상 continuation을 정확히 한 번 준비한다.
4. bare `TURN_COMPLETED`: 일반 completion이면 기존 plain continuation을 허용한다. 단, `AI_USER_ACTION_REQUIRED` 선택형 pause의 resume-preparation completion에는 NEXT_INPUT이 필수이며 누락은 protocol error다.
5. `EXTERNAL_MANUAL` pause이고 새 material signal이 없으면 사용자가 대화 밖 행동을 완료한 것으로 보고 plain CONTINUE를 준비할 수 있다.
6. `UI_MANUAL` pause이고 새 material signal이 없으면 새 CONTINUE를 만들지 않고 `pausedFromPhase`로 복귀한다. `WAIT_DRIVE_COMMIT`, ACK wait, polling, Web phase 등 기존 상태를 그대로 이어간다.
7. `AI_USER_ACTION_REQUIRED` 또는 `AI_PAUSED`이고 새 material completion이 없으면 latch를 유지한다. 자연 사용자 메시지나 재개 버튼만으로 plain CONTINUE를 생성하지 않는다.
8. Drive fetch/readback 실패, malformed signal, invalid NEXT_INPUT, anchor 불명확은 fail closed한다. Drive를 재시도·재조정하거나 pause를 유지하며 plain fallback을 만들지 않는다.
9. pause 중 completion이 기록된 경우 UI pause 여부와 무관하게 post-anchor completion을 정상 처리한다.
10. 동일 completion, 반복된 재개 버튼, process restart의 어느 시점에서도 동일 continuation side effect는 최대 한 번이어야 한다.

WebView는 기존 instance와 입력창 유지 구조를 보존한다. Drive는 completion 및 resume 상태의 authority이고 WebView는 user-role prompt 제출기다. assistant 응답 완료 판단을 DOM 감시 방식으로 되돌리지 않는다.

## Change history addition

```text
0.4.0 / protocol 0.2.0 / 2026-08-16
- TURN_COMPLETED에 optional NEXT_INPUT_B64URL wire field 추가.
- 이미 결정 가능한 user-role 입력은 USER_ACTION_REQUIRED 없이 자동 continuation하도록 사용자 조치 정의 명확화.
- 선택형 USER_ACTION_REQUIRED의 사용자 자연어 답변을 resume-preparation 턴으로 규정하고 TURN_COMPLETED+NEXT_INPUT 후 재개하도록 변경.
- 재개 버튼을 blind CONTINUE에서 durable pause-anchor 기반 Drive reconciliation으로 변경.
- UI manual pause, external manual action, AI pause/user-action latch의 no-new-signal 의미를 분리.
- WORK MODEL/REASONING authority와 TURN_INFO_REWRITE를 유지하면서 NEXT_INPUT 보존 규칙 추가.
- COMMAND_RECEIVED-first 및 at-most-once continuation 계약을 optional payload에도 동일하게 적용.
```
