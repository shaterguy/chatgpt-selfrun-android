# ChatGPT turn-state protocol

SelfRun Drive 2.2.1-dev8은 일반 Chat, Work, Pro 응답을 하나의 상태기계로 정규화하되, 네이티브 턴 완료 callback의 소유자는 기존 STOP/SEND observer 하나로 제한한다.

## 공통 상태 전이

| 상태 전이 | 권위 신호 |
| --- | --- |
| 임의 상태 → THINKING | `POST /backend-api/f/conversation` |
| THINKING → ANSWERING | Chat·Work의 `message_marker(final_channel_token, first)` 또는 final assistant message 기반 `visible_answer` |
| ANSWERING → COMPLETE | `message_stream_complete` 또는 final assistant message의 `finished_successfully + end_turn=true` |

첫 canonical POST는 `FIRST_TURN`, 그 다음 canonical POST부터는 직전 protocol 상태와 무관하게 `FOLLOWUP_TURN`으로 기록한다. 이 규칙은 Pro의 응답 stream이 Android WebView에서 완전히 관찰되지 않아 기존 STOP/SEND observer가 턴을 완료한 경우에도 다음 사용자 턴을 놓치지 않기 위한 것이다. `server_ste_metadata.metadata.is_first_turn`은 서버 분류와 로컬 분류의 불일치 진단에만 사용한다.

## 일반 Chat과 Work

`user_visible_token:first`와 `cot_token:first`는 중간 stream 신호이므로 THINKING을 유지한다. `last_token:last`는 마지막 token 부근일 뿐 완료 신호가 아니다. Work WebSocket의 `stream-item.encoded_item`은 내부 SSE payload로 파싱하지만 outer `done`은 상태 완료를 발생시키지 않는다.

유효한 semantic completion은 별도 URL callback을 만들지 않는다. protocol observer는 기존 `window.__selfRunDriveTurnObserver`에 `allowIdleBaseline=true`를 적용하고 그 observer의 `evaluate()`를 호출한다. 실제 5초 안정성 검사, 중복 억제, observer 해제와 `selfrun-drive://turn-completed` callback은 기존 STOP/SEND observer만 수행한다.

## Pro

Pro도 canonical conversation POST에서 THINKING을 시작한다. `stream_handoff`는 fetch stream에서 WebSocket stream으로 전달 경로가 바뀌는 신호일 뿐 상태 전이가 아니다. Pro에 `final_channel_token:first`가 없더라도 final assistant message가 나타나면 이를 `visible_answer`로 정규화하여 ANSWERING으로 전이한다.

`encoded_item` 내부의 `data: [DONE]`은 개별 SSE stream 종료이므로 전체 assistant turn의 COMPLETE로 처리하지 않는다. outer WebSocket `done`도 완료 판정에 사용하지 않는다. final-answer 근거가 없는 조기 `message_stream_complete`는 `completion_ignored`로 기록하되 STOP/SEND observer의 `fired`, MutationObserver, timer 또는 binding을 변경하지 않는다. 이후 Pro stream을 protocol observer가 보지 못하더라도 기존 observer가 계속 턴 완료를 판정할 수 있다.

## 회귀 검증

`TurnProtocolStateWebViewTest`는 Android WebView에서 다음 순서를 직접 실행한다.

1. Chat 최초 턴과 Work 후속 턴의 canonical POST, 중간 marker, final-channel, semantic completion
2. Pro 최초 턴의 stream handoff, 내부 `[DONE]`, final-answer 근거 없는 조기 semantic completion과 fallback 생존
3. 조기 completion 뒤 다음 canonical POST가 후속 턴으로 정상 증가하는 조건
4. Pro 후속 턴의 final assistant `visible_answer`, semantic completion, outer `done`
5. protocol completion과 기존 observer 사이에 직접 callback·observer 해제·이중 완료 경로가 없는 조건
6. `/conversation/prepare`, `/backend-api/conversation/init`, `/backend-api/f/responses`가 새 턴을 시작하지 않는 조건

TEST 워크플로의 2.x instrumentation profile은 `SelfRunAndroidTestRunner`를 통해 이 회귀 테스트를 항상 포함한다.
