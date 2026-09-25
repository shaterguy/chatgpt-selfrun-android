# SelfRun Termux Server

SelfRun Android 앱에서 브라우저 실행 책임을 분리하기 위한 태블릿 로컬 서버입니다.

## 현재 책임

- Android 앱에서 START/NEXT/PAUSE/STOP 신호 수신
- SelfRun 최소 envelope를 ChatGPT 입력문으로 조립
- Chromium DevTools Protocol로 새 ChatGPT 대화 탭 생성
- 입력창 탐색, 입력 반영 확인, 전송
- 응답 진행 상태를 RUNNING/STALLED/COMPLETED/PAGE_ERROR로 감시
- NEXT 수신 즉시 이전 감시 취소 및 이전 탭 폐기
- generation + signalId 이중 확인으로 늦은 이전 이벤트 폐기
- 상태를 $HOME/.selfrun-server/state.json에 영속 저장
- 127.0.0.1 전용 바인딩과 Bearer 토큰 인증

## 실행

```sh
cd $HOME/work/selfrun-termux-server/termux-server
npm start
```

기본 포트는 17831입니다. 상태 확인은 다음과 같습니다.

```sh
curl http://127.0.0.1:17831/health
```

## 신호 API

`POST /v1/signals`로 START, NEXT, PAUSE, STOP 신호를 받습니다.
START/NEXT의 본문 예시는 다음 구조입니다.

```json
{
  "signalId": "unique-signal-id",
  "type": "NEXT",
  "projectUrl": "https://chatgpt.com/g/<project>/project",
  "envelope": {
    "TASK_ID": "SR-...",
    "TURN_ID": "SR-...:turn:2",
    "REQUEST_ID": "SR-...:turn:2-request",
    "SELF_RUN_SKILL_DOCUMENT_ID": "...",
    "RESULT_DOCUMENT_ID": "...",
    "REQUIREMENT_DOCUMENT_ID": "...",
    "PREVIOUS_RESULT_DOCUMENT_ID": "..."
  }
}
```

서버는 envelope에 정의된 필드만 ChatGPT 입력문으로 만듭니다.
PHASE, TASK_MODE, SIGNAL_TYPE 같은 정보성 필드는 전달하지 않습니다.

## NEXT 선점 규칙

NEXT를 받으면 다음 순서를 보장합니다.

1. 현재 감시 객체에 즉시 취소 신호를 보냅니다.
2. generation을 증가시켜 이전 대화방 이벤트를 무효화합니다.
3. 기존 Chromium 탭을 닫습니다.
4. 새 프로젝트 대화 탭을 생성합니다.
5. 새 envelope를 입력하고 전송합니다.
6. 새 generation의 대화만 감시합니다.

상태 저장 직전에도 generation과 signalId를 재확인하므로 늦게 도착한 이전 이벤트가
새 턴 상태를 덮어쓸 수 없습니다.

## 태블릿 상시 실행

현재 배포는 tmux 감독 프로세스와 Termux:Boot 스크립트를 사용합니다.

- 감독 스크립트: `$HOME/bin/selfrun-server-supervisor.sh`
- 부팅 스크립트: `$HOME/.termux/boot/20-selfrun-server.sh`
- 로그: `$HOME/.selfrun-server/server.log`
- 상태: `$HOME/.selfrun-server/state.json`
