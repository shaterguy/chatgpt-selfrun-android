# SelfRun Termux Server 4.0.0-dev1

SelfRun Android 앱의 상태머신과 UI는 그대로 두고, ChatGPT 웹 실행과 장시간 대화 감시만 Galaxy Tab Termux 서버로 분리합니다.

## 책임 경계

Android 앱이 계속 담당합니다.

- 사용자 명령과 기존 UI
- Requirement / Result / Handoff Drive 문서
- PLAN / WORK / VERIFY / REPAIR / DONE 판정
- 기존 90초 대화방 생성 watchdog과 5초 복구 재시도
- Vercel + FCM Result 변경 알림
- 현재 대화방 URL을 앱 상태에 고정하고 열기 기능 제공

Termux 서버가 담당합니다.

- Drive 기준 폴더의 `.selfrun-dispatch.json` 감시
- Headless Chromium에서 프로젝트 새 대화 준비
- 앱의 기존 CLAIM_SEND 이후에만 프롬프트 전송
- canonical `https://chatgpt.com/c/<id>` 확보 후 Drive에 기록
- 대화방 liveness 감시
- 설정된 무활동 시간이 지나면 같은 대화방에 진행 요청 후 계속 감시
- 서버 재시작 후 STARTED 대화방 재접속
- Android가 `RESULT_COMMITTED`를 기록하면 해당 대화 감시 종료

## Drive dispatch 상태

```text
PREPARE_REQUESTED
  -> READY_TO_SUBMIT
  -> SEND_REQUESTED
  -> SUBMITTED
  -> STARTED
  -> RESULT_COMMITTED
```

브라우저 오류는 `ERROR`로 기록합니다. 동일 TURN의 앱 90초 복구는 `attempt`를 증가시켜 같은 dispatch 파일에 새 요청을 기록합니다. 서버는 더 높은 attempt를 발견하면 이전 Chromium 세션을 폐기합니다.

## 기본 설정

- Drive remote: `selfrun-drive:`
- Drive 실행폴더: `GPT/Self Run/Runs`
- Drive 감시 주기: 2초
- ChatGPT 시작 확인 제한: 90초
- liveness 복구: 5분
- 복구 프롬프트: `계속 진행해.`
- Chromium: headless
- rclone: 지속 `rcd` 세션 + Termux private Unix socket

환경변수로 변경할 수 있습니다.

- `SELFRUN_DRIVE_REMOTE`
- `SELFRUN_DRIVE_RUNS_ROOT`
- `SELFRUN_DRIVE_POLL_MS`
- `SELFRUN_LIVENESS_RECOVERY_MS`
- `SELFRUN_RECOVERY_PROMPT`
- `SELFRUN_START_CONFIRMATION_TIMEOUT_MS`

## 실행

```sh
cd $HOME/work/selfrun-termux-server/termux-server
npm start
```

헬스체크:

```sh
curl http://127.0.0.1:17831/health
```

외부 네트워크에서 앱이 이 포트에 접속하지 않습니다. 앱과 서버 사이의 실행 메시지는 Google Drive만 사용합니다.
