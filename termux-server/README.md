# SelfRun Termux Server 4.0.0-dev2

SelfRun Android 앱의 Task/Turn/Result 상태머신은 그대로 유지하고, ChatGPT 브라우저 실행과 liveness 감시만 태블릿 Termux 서버로 분리합니다.

## 통신 구조

앱과 서버는 직접 네트워크 통신하지 않습니다.

1. 앱이 기존 Task 폴더에 `__SELFRUN_DISPATCH__*.json` 파일을 생성합니다.
2. 서버가 Google Drive를 24시간 감시해 새 dispatch를 발견합니다.
3. 서버는 앱의 `CREATE_REQUESTED`를 받아 새 대화를 준비하고 `READY_TO_SUBMIT`을 기록합니다.
4. 기존 앱과의 호환을 위해 앱의 `SEND_REQUESTED`를 받은 뒤 실제 입력을 전송합니다. 현재/이전 Result, phase, Handoff는 전송 자격 조건으로 검사하지 않습니다.
5. 대화 URL은 발견 즉시 기록하되, 사용자 입력이 실제 반영됐다는 readback 이후에만 `STARTED`를 기록합니다. 불확실한 초기 전송은 `PENDING`으로 남기며 같은 입력을 무작정 다시 보내지 않습니다.
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

서버의 앱 프롬프트 재작성과 설정된 시작/진행 지시는 유지합니다. 응답 완료 또는 Result committed는 감시 종료가 아닙니다. 서버는 같은 대화를 계속 관찰하고 앱의 다음 턴 신호 또는 명시적인 중지·일시정지 신호를 따릅니다. Result committed 또는 읽기 실패일 때 진행 멘트를 억제하는 기존 안전 조건은 유지합니다.

앱 STOP은 먼저 이전 브라우저 작업을 무효화하고 해당 대화의 생성 중지를 수행합니다. 중지 확인 결과는 추가 필드 stop_status로 기록하며, 중지 실패를 성공으로 표시하지 않습니다. response_status는 응답 관측 정보이며 앱의 작업 완료 판단을 대체하지 않습니다.

앱 CANCELLED/SUPERSEDED는 해당 시도를 폐기합니다. 같은 RUNNING 제어만으로 되살리지 않으며 새 dispatch_attempt를 받더라도 이미 전송됐거나 불확실한 입력은 재전송하지 않고 기존 증거를 확인합니다. ID는 상관관계 값으로 취급하며 서버가 turn 번호 또는 이전 턴 문서 의미를 해석해 실행을 막지 않습니다.
