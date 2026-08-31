# ChatGPT turn-state protocol

SelfRun Drive 2.2.1-dev9부터 일반 Chat, Work, Pro 응답을 하나의 상태기계로 정규화하고, 그 상태를 Run Console의 사용자 상태 표시에도 직접 사용한다. Android WebView의 protocol-first observer가 사용하는 신호와 비신호는 아래와 같다.

## 공통 상태 전이

| 상태 전이 | 권위 신호 |
| --- | --- |
| IDLE 또는 COMPLETE → THINKING | `POST /backend-api/f/conversation` |
| THINKING → ANSWERING | Chat·Work의 `message_marker(final_channel_token, first)` 또는 final assistant message 기반 `visible_answer` |
| ANSWERING → COMPLETE | `message_stream_complete` 또는 final assistant message의 `finished_successfully + end_turn=true` |

첫 canonical POST는 `FIRST_TURN`, COMPLETE 뒤의 다음 canonical POST는 `FOLLOWUP_TURN`으로 기록한다. `server_ste_metadata.metadata.is_first_turn`은 서버 분류와 로컬 분류의 일치 여부를 확인하는 보조 근거다.

## 일반 Chat과 Work

`user_visible_token:first`와 `cot_token:first`는 중간 stream 신호이므로 THINKING을 유지한다. `last_token:last`는 마지막 token 부근일 뿐 완료 신호가 아니다. Work WebSocket의 `stream-item.encoded_item`은 내부 SSE payload로 파싱하지만 outer `done`은 상태 완료를 발생시키지 않는다.

## Pro

Pro도 canonical conversation POST에서 THINKING을 시작한다. `stream_handoff`는 fetch stream에서 WebSocket stream으로 전달 경로가 바뀌는 신호일 뿐 상태 전이가 아니다. Pro에 `final_channel_token:first`가 없더라도 final assistant message가 나타나면 이를 `visible_answer`로 정규화하여 ANSWERING으로 전이한다.

`encoded_item` 내부의 `data: [DONE]`은 개별 SSE stream 종료이므로 전체 assistant turn의 COMPLETE로 처리하지 않는다. outer WebSocket `done` 역시 완료 판정의 권위 신호가 아니다.

Pro에서는 final answer evidence보다 `message_stream_complete`가 먼저 관찰될 수 있다. 이 경우 protocol phase는 THINKING을 유지하고 `completion_ignored`를 기록하지만 STOP/SEND DOM fallback을 중단하지 않는다. 이후 final answer semantic event가 도착하면 ANSWERING으로 진행하고, semantic completion이 다시 오거나 DOM이 안정된 idle 완료를 확인하면 기존 단일 completion callback 경로로 수렴한다.

## Run Console 상태 표시

Run Console의 상단 상태는 서비스 내부 구현 문구를 그대로 노출하지 않는다. 현재 Run ID와 일치하는 protocol event를 기준으로 다음 상태를 우선 표시한다.

- `THINKING`: `추론 중`
- final answer evidence 전 조기 completion: `답변 시작 대기 중`
- `ANSWERING`: `답변 생성 중`
- `COMPLETE` 또는 DOM 완료 후 Drive 동기화 단계: `답변 완료 · 차기 턴 대기`
- 차기 턴 설정·전송 단계는 각각 `차기 턴 설정 중`, `차기 턴 전송 중`

내부 phase, protocol phase와 마지막 protocol event는 `실행 정보`에서 진단용으로 확인한다. 서로 다른 Run의 잔류 protocol event는 UI 상태로 수용하지 않는다.

## 회귀 검증

`TurnProtocolStateWebViewTest`는 Android WebView에서 Chat·Work·Pro의 canonical POST, intermediate marker, visible answer와 semantic completion 순서를 검증한다. `ProEarlyCompleteFallbackWebViewTest`는 Pro에서 final answer보다 먼저 `message_stream_complete`가 도착했을 때 DOM fallback이 살아 있고 이후 ANSWERING/COMPLETE로 진행할 수 있는지 직접 검증한다.

TEST 워크플로의 2.x instrumentation profile은 `SelfRunAndroidTestRunner`를 통해 이 회귀 테스트들을 항상 포함한다.
