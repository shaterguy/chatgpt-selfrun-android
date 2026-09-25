# SelfRun Termux Server 4.0.0-dev1

SelfRun Android 앱의 Task/Turn/Result 상태머신은 그대로 유지하고, ChatGPT 브라우저 실행과 liveness 감시만 태블릿 Termux 서버로 분리합니다.

## 통신 구조

앱과 서버는 직접 네트워크 통신하지 않습니다.

1. 앱이 기존 Task 폴더에 `__SELFRUN_DISPATCH__*.json` 파일을 생성합니다.
2. 서버가 Google Drive를 24시간 감시해 새 dispatch를 발견합니다.
3. 서버가 Headless Chromium에서 대상 프로젝트의 새 대화 준비를 끝내고 같은 파일을 `READY_TO_SUBMIT`으로 갱신합니다.
4. 앱은 기존 CLAIM_SEND 상태전이 후 같은 파일을 `SEND_REQUESTED`로 갱신합니다.
5. 서버가 프롬프트를 전송하고 canonical conversation POST와 URL을 확인한 뒤 `STARTED`와 `conversation_url`을 기록합니다.
6. 앱은 URL을 기존 ledger의 `conversationUrl` resource로 고정하고 기존 Result/Vercel 흐름으로 전환합니다.

따라서 앱의 기존 90초 대화방 준비 watchdog, 5초 재시도, Result 판정, REPAIR/후속 TURN/DONE 판정, Vercel/FCM 알림은 유지됩니다.

## Drive dispatch 상태

앱 → 서버:

- `CREATE_REQUESTED`: 대화 준비 요청
- `SEND_REQUESTED`: 기존 앱의 CLAIM_SEND 완료 후 실제 전송 요청
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

서버는 응답 텍스트, 전체 페이지 텍스트, streaming 표시의 변화를 활동으로 봅니다. 기본 5분 동안 활동이 없고 응답이 아직 진행 중이면 같은 대화방에 `계속 진행해`를 입력하고 다시 감시합니다. 값은 환경변수로 조정할 수 있습니다.

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
