# SelfRun Termux Server 4.0.0-dev9

SelfRun Android 앱의 Task/Turn/Result 상태머신은 그대로 유지하고, ChatGPT 브라우저 실행과 liveness 감시만 태블릿 Termux 서버로 분리합니다.

## 통신 구조

앱과 서버는 직접 네트워크 통신하지 않습니다.

1. 앱이 기존 Task 폴더에 `__SELFRUN_DISPATCH__*.json` 파일을 생성합니다.
2. 서버가 Google Drive를 24시간 감시해 새 dispatch를 발견합니다.
3. 서버는 앱의 `CREATE_REQUESTED`를 받아 새 대화를 준비하고 `READY_TO_SUBMIT`을 기록합니다.
4. 기존 앱과의 호환을 위해 앱의 `SEND_REQUESTED`를 받은 뒤 실제 입력을 전송합니다. 현재/이전 Result, phase, Handoff는 전송 자격 조건으로 검사하지 않습니다.
5. 소유한 대화의 canonical URL은 발견 즉시 `STARTED`와 함께 앱에 전달하고 현재 페이지 감시를 시작합니다. 입력 ACK가 없어도 감시를 계속하며, 실제 수신 여부는 `post_outcome=UNKNOWN`으로 별도 보존합니다. 같은 초기 입력을 다시 보내거나 새 대화를 만들지 않습니다.
6. 앱은 URL을 기존 ledger의 `conversationUrl` resource로 고정하고 기존 Result/Vercel 흐름으로 전환합니다.

따라서 앱의 기존 90초 대화방 준비 watchdog, 5초 재시도, Result 판정, REPAIR/후속 TURN/DONE 판정, Vercel/FCM 알림은 유지됩니다.

## Drive dispatch 상태

앱 → 서버:

- `CREATE_REQUESTED`: 대화 준비 요청
- `SEND_REQUESTED`: 준비된 대화에 실제 입력을 전송하라는 앱 요청
- `CANCELLED`: 90초 timeout, pause, stop 등으로 현재 시도 폐기

서버 → 앱:

- `PENDING`
- `READY_TO_SUBMIT`
- `STARTED` + `conversation_url`
- `RUNNING`
- `STALLED`
- `RECOVERY_SENT`
- `COMPLETED`
- `ERROR`
- `SUPERSEDED`

## liveness

서버는 실제 사용자·어시스턴트 메시지 식별자와 텍스트 길이, streaming/재개 상태의 변화를 활동으로 봅니다. 기본 10분 동안 실질 변화가 없을 때 Task CONTROL이 `RUNNING`인지 먼저 확인합니다. `PAUSED`, `WAITING_USER_INTERVENTION`, `STOPPED`, `DONE`, `RESUME_REQUESTED`, `RESUME_STOPPED_REQUESTED` 상태에서는 liveness 복구를 수행하지 않습니다. 명시적인 앱 중지·일시정지 지시가 없을 때 Result 미완료와 최신 메시지 식별자 불변을 재확인한 뒤 같은 대화방에 `현재 턴에 할당된 잔여작업이 있으면 계속 수행해`를 입력하고 다시 감시합니다. 시간 값은 환경변수로 조정할 수 있습니다.

## 실행

```sh
cd $HOME/work/selfrun-termux-server/termux-server
npm test
npm run check
npm start
```

기본 Drive 감시 경로는 `selfrun-drive:GPT/Self Run/Runs`이며 `SELFRUN_DRIVE_RUNS_PATH`로 변경할 수 있습니다.

상시 실행 배포 파일:

- `deploy/selfrun-server-supervisor.sh`
- `deploy/20-selfrun-server.sh`

서버는 외부 공개가 필요하지 않습니다. Google Drive만 앱↔서버 메시지 버스로 사용합니다.

## 역할 경계와 중지

서버의 앱 프롬프트 재작성과 설정된 시작/진행 지시는 유지합니다. 응답이 잠시 멈춘 것만으로 감시를 종료하지 않습니다. 현재 Result의 committed:true가 스키마·식별자·일치하는 재읽기로 확인되면 해당 요청을 COMMITTED로 소비하고 감시와 소유 탭을 즉시 정리합니다. 소비된 요청은 재시작·이전 RUNNING 갱신·재수신으로 되살리지 않으며 Result를 다시 읽거나 진행 멘트를 보내지 않습니다. 새로운 작업은 앱이 보낸 새 turn/request/Result로만 시작합니다. 앱의 새 요청이 먼저 도착해 이전 관측이 분리되어도, 이미 실행된 이전 요청은 자신의 정확한 Result만 독립적으로 재확인해 소비합니다. 이 정리 확인은 다음 요청의 실행을 기다리게 하지 않으며 이전 탭을 재연결하거나 입력을 보내지 않습니다. 실패·미완료 확인의 재시도 시각은 재시작에도 유지됩니다. Result 읽기 실패는 완료로 간주하지 않고 진행 멘트를 억제합니다.

앱 STOP은 이전 로컬 감시·재시도 작업을 무효화하고 소유가 확인된 탭을 바로 닫습니다. 제출 영수증 재확인이나 생성 중지 버튼 조작은 하지 않습니다. stop_status=NOT_REQUESTED는 원격 생성 중지를 시도하지 않았다는 뜻이며, ChatGPT 서버의 생성은 계속될 수 있습니다. response_status는 응답 관측 정보이며 앱의 작업 완료 판단을 대체하지 않습니다.

앱 CANCELLED/SUPERSEDED는 해당 시도를 폐기합니다. 같은 RUNNING 제어만으로 되살리지 않으며 새 dispatch_attempt를 받더라도 이미 전송됐거나 불확실한 입력은 재전송하지 않고 기존 증거를 확인합니다. ID는 상관관계 값으로 취급하며 서버가 turn 번호 또는 이전 턴 문서 의미를 해석해 실행을 막지 않습니다.

앱 STOP/DONE 또는 취소로 작업이 끝나면 SelfRun이 직접 만든 것으로 확인된 탭을 즉시 정리합니다. 실패한 탭 정리는 실행 작업과 분리해 재시작 후 다시 시도합니다. 수동으로 연 탭, 실행·대기·일시정지 중인 작업, 소유 증거가 없는 예전 탭은 닫지 않습니다. 모든 활성·대기 작업과 다른 브라우저 사용이 사라진 경우에만 정확히 소유가 확인된 Chromium 프로세스도 종료하며, SelfRun 서버·worker·supervisor는 유지합니다. ChatGPT 대화 기록은 삭제하지 않습니다.

## 4.0.0-dev9: terminal records are removed

Finished, stopped and cancelled server records, associated HTTP inputs/signals, operational events and derived queues are removed. No historical identity hash, denylist, terminal epoch watermark or alternate operational archive grants or denies a later app request. Terminal finalization may use its current in-flight cleanup/publication context; it is not persisted as restart input and is discarded when finalization settles, including a failed projection attempt. A pending browser acquisition remains fenced until its current callback settles.

Startup retains ongoing nonterminal request state and its existing receipt/recovery protections. It drops ended records instead of rebuilding cancellation/publication queues from them. A planned patch requires current work and acquisition to be absent and preserves the original state/events in a private backup outside the live data directory. Exact generation, control set/epochs and cutoff guards refuse newly arrived work before maintenance writes.

The unchanged Android app already supplies the resume contract. A fresh execution clears the conversation URL and sends a new `turnId:resume:operationUUID` request with `RUNNING/DISPATCH_CREATE` control and attempt 1; the server prepares a new conversation. `RUNNING/STAGE_WAITING` with the same task/turn/request and a canonical conversation URL attaches that current conversation for observation without resending a prompt or requiring an old retired marker. Android's pinned Result waiting branch remains unchanged.

Current STOP/DONE or a committed authoritative Result finishes local work. A successfully resolved absence (`DRIVE_FILE_NOT_FOUND`) clears the corresponding current request; temporary transport/auth failures suspend or retain ongoing recovery and do not claim absence. Export failures, including ambiguous HTTP 404, are not treated as proof that a Result document is gone. External user Result/requirement documents are not deleted. Browser, extension, app, account and permission code is unchanged.

The old offline helper name `quarantineIdleSnapshot` delegates to removal and creates no quarantine. `rollbackIdleSnapshot` refuses source rollback that could reintroduce history; operations must use reviewed forward recovery. Legacy operational quarantine/hash keys are removed during migration.
