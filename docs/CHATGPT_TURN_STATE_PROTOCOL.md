# ChatGPT turn-state protocol

SelfRun Drive 2.2.1-dev7부터 일반 Chat, Work, Pro 응답을 하나의 상태기계로 정규화한다. 이 문서는 Android WebView의 protocol-first observer가 사용하는 신호와 비신호를 기록한다.

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

`encoded_item` 내부의 `data: [DONE]`은 개별 SSE stream 종료이므로 전체 assistant turn의 COMPLETE로 처리하지 않는다. 최종 완료는 뒤이어 도착하는 `message_stream_complete`가 담당한다. outer WebSocket `done` 역시 완료 판정의 권위 신호가 아니다.

## 회귀 검증

`TurnProtocolStateWebViewTest`는 Android WebView에서 다음 순서를 직접 실행한다.

1. Chat 최초 턴과 Work 후속 턴의 canonical POST, 중간 marker, final-channel, semantic completion
2. Pro 최초·후속 턴의 stream handoff, 내부 `[DONE]`, final assistant `visible_answer`, `message_stream_complete`, outer `done`
3. `/conversation/prepare`, `/backend-api/conversation/init`, `/backend-api/f/responses`가 새 턴을 시작하지 않는 조건

TEST 워크플로의 2.x instrumentation profile은 `SelfRunAndroidTestRunner`를 통해 이 회귀 테스트를 항상 포함한다.
