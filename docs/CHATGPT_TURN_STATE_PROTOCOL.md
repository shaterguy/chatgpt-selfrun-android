# ChatGPT response-state and Drive signal protocol

SelfRun Drive 2.2.1-dev10부터 실행 제어에서 ChatGPT 턴 번호와 Drive signal cursor를 권위 기준으로 사용하지 않는다. 런타임은 다음 두 가지 현재성 규칙만 사용한다.

1. ChatGPT에서는 현재 run·turn token에 처음 허용된 `POST /backend-api/f/conversation` 하나가 terminal COMPLETE까지 현재 응답 요청이다.
2. Drive에서는 현재 Job 폴더에 존재하는 canonical signal Google Docs 중 아직 인식하지 않은 **Drive file ID**가 새 결과다.

## ChatGPT 응답 상태

| 상태 전이 | 권위 신호 |
| --- | --- |
| 임의 상태 → THINKING | 가장 최근 `POST /backend-api/f/conversation` |
| THINKING → ANSWERING | 일반 Chat·Work는 `message_marker(final_channel_token, first)`; Pro는 현재 assistant message의 실제 non-empty text snapshot 또는 해당 message의 text delta |
| THINKING/ANSWERING → COMPLETE | 현재 요청과 상관된 `message_stream_complete` + `final_channel_token` 또는 실제 assistant final text 증거 |

동일 run에서 THINKING/ANSWERING 중 새 turn token 바인딩은 거부한다. 앱은 현재 응답의 terminal COMPLETE 전에는 다음 canonical POST를 제출하지 않으며, 충돌이 감지되면 같은 conversation을 보존한 채 일시정지한다.

이전 fetch 응답은 해당 요청에 부여된 일회성 request identity가 현재 identity와 다르면 폐기한다. identity 없는 socket/subframe payload는 conversation ID와 work turn ID가 모두 현재 요청에 결합된 경우에만 허용한다. Work/Pro WebSocket은 새 요청이 시작될 때 이전 `turn_id`를 폐기 목록에 넣어 늦게 도착한 stream을 현재 응답으로 오인하지 않는다. 이 identity들은 순번이 아니며 현재 요청과 폐기된 요청을 구분하는 용도로만 사용한다.

`user_visible_token:first`, `cot_token:first`, `last_token:last`, `stream_handoff`, encoded-item 내부 `[DONE]`, outer WebSocket `done`, `finished_successfully + end_turn=true`는 전체 응답 COMPLETE를 만들지 않는다. Pro의 ANSWERING 보조 신호는 assistant role이며 channel이 없거나 `final`인 message의 실제 non-empty string text로 제한한다. empty/whitespace text와 user/tool/analysis/commentary payload는 제외한다.

현재 request identity 또는 conversation ID·work turn ID fence를 통과한 `message_stream_complete`라도 `final_channel_token` 또는 실제 assistant final text 증거보다 먼저 도착하면 COMPLETE로 전이하지 않고 `completion_ignored`로 기록한다. 같은 활성 요청은 THINKING/ANSWERING 상태를 유지하며 후속 reasoning/final payload를 계속 수용하고, final-answer evidence가 확인된 뒤 도착한 `message_stream_complete`만 COMPLETE의 권위 terminal 신호로 인정한다. `message_stream_complete` 자체에 assistant final text가 포함되면 동일 semantic inspection 안에서 그 text를 evidence로 인정할 수 있다. 늦은 이전 요청과 폐기된 turn ID의 payload는 계속 무시하며 DOM 상태는 사용하지 않는다.

## Drive signal document 현재성

신규 SelfRun은 한 signal을 한 Google Docs 파일로 기록한다. 앱은 Job 폴더를 조회하여 canonical signal document의 Drive file ID를 수집하고, 이미 인식한 ID 집합과 비교한다.

- 기존에 인식한 ID: 다시 실행하지 않는다.
- 처음 보는 ID: 새 signal로 처리한다.
- 파일 정렬 위치가 바뀌거나 과거 문서가 삭제되어도 ID가 같으면 재처리하지 않는다.
- 과거 `driveSignalCursor` 값이 현재 파일 개수보다 크거나 작다는 이유로 실행을 중단하지 않는다.

기존 설치본에서 dev10으로 처음 올라오는 경우에만 `lastSeenDriveVersion`의 `signal:<fileId>:...` 값을 우선 이용해 기존 ID baseline을 복원한다. 그 정보가 없는 오래된 실행에는 기존 cursor를 최초 baseline 마이그레이션에 한 번 사용할 수 있지만, 이후 정상 실행 판단에는 사용하지 않는다.

## 정상 완료 후 흐름

ChatGPT 응답 COMPLETE가 확인되면 앱은 Job 폴더를 조회한다.

1. 이전에 인식하지 못한 새 canonical signal document ID가 있으면 해당 signal을 처리한다.
2. `TURN_COMPLETED`이면 필요한 다음 요청 설정을 적용하고 CONTINUE를 전송한다.
3. `PAUSED`, `USER_ACTION_REQUIRED`, `DONE`이면 해당 제어 signal을 적용한다.
4. 새 signal ID가 아직 없으면 제한시간 동안 Drive를 재확인한다.

## 일시정지 후 재개

재개 시에도 같은 file-ID 차집합 규칙을 사용한다.

- 새 signal document ID가 있으면 그 최신 결과를 반영하고 CONTINUE 경로를 준비한다.
- 새 signal document ID가 없으면 과거 cursor나 턴 번호를 맞추려 하지 않고 바로 CONTINUE 준비로 이동한다.

## Run Console

사용자 화면은 내부 ordinal을 노출하지 않는다.

- `추론 중`
- `답변 시작 대기 중`
- `답변 생성 중`
- `답변 완료 · 새 Drive 신호 확인 중`
- `다음 요청 설정 중`
- `CONTINUE 전송 중`
- `재개 · 새 Drive 신호 확인 중`

실행 정보에는 내부 phase, 응답 protocol phase/event, 마지막 인식 signal document ID를 제공한다. `SelfRun Turn`, `ChatGPT Turn`, `Drive signal cursor`는 표시하지 않는다.

## 회귀 검증

- `TurnProtocolStateWebViewTest`: 활성 응답 중 새 canonical POST가 들어왔을 때 최신 요청으로 교체되고 이전 fetch/WebSocket 데이터가 폐기되는지 검증한다. Pro에서는 final-answer evidence보다 먼저 온 `message_stream_complete`가 무시되고 같은 활성 request/work turn의 최종 답변과 후속 terminal event에서만 COMPLETE 되는지도 검증한다.
- `ProtocolDetachedSurfaceWebViewTest`: Surface detach 상태에서 token-correlated THINKING→ANSWERING→COMPLETE와 native callback을 검증합니다.
- `DriveSignalDocumentIdentityAndroidTest`: 비정상적으로 큰 과거 cursor, 파일 정렬 변화, 재개 시 신규 ID 부재에서도 Drive file ID 기준으로 unseen signal을 계산하는지 검증한다.
- `SelfRunAndroidTestRunner`: 2.x TEST canonical instrumentation 경로에 위 회귀 테스트를 강제로 포함한다.


## 제출 확인과 대화 보존

현재 run의 clicked marker와 신뢰된 project/general route에서 새 `/c/<id>`가 확인되면 composer가 추론 중 숨겨져 있어도 bootstrap 제출을 확정한다. current-token protocol generation이 확인된 60초 경계는 같은 conversation의 WAIT로 승격하며, route만 확인되었거나 결과가 불확실하면 같은 conversation을 보존한 채 일시정지한다. `BOOTSTRAP_SUBMISSION_TIMEOUT`과 HTTP 429는 successor run이나 새 conversation을 생성하지 않는다.
