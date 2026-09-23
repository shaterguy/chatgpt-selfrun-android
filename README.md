# SelfRun 3

SelfRun 3는 Android 앱 안에서 ChatGPT 대화와 Google Drive 결과물을 하나의 논리 작업으로 관리하는 ledger 기반 실행기입니다. 현재 활성 개발 계보는 V3 하나뿐이며, V1/V2 실행 상태머신·Drive title signal·rollover·legacy migration을 런타임 호환 경로로 유지하지 않습니다.

현재 정식 버전은 SelfRun Drive 3.3.1 / versionCode 3034000입니다. 검증된 3.3.1-dev3의 기능 소스를 정식 계보로 승격했으며, 정식 3.3.0에서 3.3.1로 인플레이스 업데이트할 수 있습니다. 최신 정식 Release는 [drive-v3.3.1](https://github.com/shaterguy/chatgpt-selfrun-android/releases/tag/drive-v3.3.1)입니다.

## 디버그 로그와 턴별 바로가기

- 대화 주소와 제출이 확인되면 Result 대기 전에 작업 폴더의 단일 `<TASK_ID>-debug-log` Google Doc 갱신을 요청합니다. 이후 턴도 같은 문서를 갱신합니다.
- DONE, 일시정지, 중지, 사용자 개입 대기에도 마지막 이벤트를 포함한 갱신을 요청합니다. 로그 인증·네트워크·저장 실패는 실행을 멈추거나 인증 창을 열지 않으며, 다음 요청에서 누적 내용을 다시 보냅니다.
- 전송 요청과 누적 로그는 앱 전용 저장소에 보존하며 서비스 종료 후에도 보조 작업이 처리합니다. 업로드 스레드와 실행 원장은 분리됩니다. 이 버전에서 기록한 정제 로그는 기존 화면용 로그의 1 MiB 회전과 무관하게 누적됩니다. 이전 버전에서 이미 잘린 기록은 복원하지 않습니다.
- 대화 이력의 작은 `대화 열기`와 `작업문서` 버튼은 각 행의 대화와 Result 문서만 엽니다. 문서 ID가 없는 이전 행은 작업문서 버튼을 비활성화합니다.
- committed DONE Result에 선택적으로 기록된 `deliverable_links`는 실행 원장에 보존되며 실행 정보·상세 화면·대화 이력에서 최종 산출물 링크로 복원해 표시합니다.
- 메인 화면에서 `실행 정보`를 펼치면 길게 눌러 선택·복사할 수 있습니다. 표시 내용이 같으면 주기 갱신이 선택 영역을 초기화하지 않습니다.

## 실행 구조

- `SelfRun3Engine`: Task → logical Turn → physical Request의 상태 전이를 정의합니다.
- `SelfRun3Ledger`: 작업 스냅샷, 이벤트, 턴 결과를 SQLite 원장에 기록합니다.
- `SelfRun3Coordinator`: 원장을 기준으로 Drive 준비, ChatGPT 전송, 응답 관측, 결과 reconciliation을 조정합니다.
- `SelfRun3DriveAdapter`: 요구사항·첨부·턴별 결과 문서를 고정 ID로 관리합니다.
- `SelfRun3WebAdapter`: 앱 전용 WebView에서 CHAT/WORK 프로필 적용, 제출, 응답 protocol 관측을 수행합니다.
- `SelfRunStore`: V3 원장의 사용자 화면 projection, Drive base binding, 첨부 URI 소유권만 보관합니다.

앱의 authoritative execution state는 `SelfRun3Ledger`에 있으며 `SelfRunStore`는 별도의 구형 실행 상태머신이 아닙니다.

## AI 계약

SelfRun 3 프롬프트는 Google Drive의 canonical 운영문서를 복제하지 않고, 각 새 conversation에서만 결정되는 dynamic envelope를 전송합니다.

```text
[SELF_RUN_V3 3.1.0]
SELF_RUN_SKILL_DOCUMENT_ID=1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo
TASK_ID=...
TURN_ID=...
REQUEST_ID=...
TURN=...
PHASE=...
TASK_MODE=...
MODE=...
EXECUTION_KIND=...
SIGNAL_TYPE=...
RESULT_DOCUMENT_ID=...
REQUIREMENT_DOCUMENT_ID=...
PREVIOUS_RESULT_DOCUMENT_ID=...
FOLDER_ID=...
RECEIPT=[SELF_RUN_3_RESULT ...]
EXECUTION_PROFILE={"mode":"...","model":"...","reasoning":"..."}
```

고정 phase·checkpoint·Result commit·Branch/Merge/Repair/User Action 의미론은 `SELF_RUN_SKILL_DOCUMENT_ID`의 최신 전체 본문이 유일한 원본입니다. 최초 요구사항과 초기 Result identity는 각각 지정된 Drive 문서에서 읽으므로 프롬프트에 다시 넣지 않습니다. 생성 프롬프트에는 profile 후보목록을 삽입하지 않으며, 다음 실행은 직전 Result의 `next_execution.profile` 또는 `next_profile`에 기록된 model/reasoning을 원본으로 사용합니다. ProfileRegistry는 그 선택값을 실제 request profile로 검증·변환합니다. V3 초기 준비 단계는 과거 Chat bootstrap 설정을 적용하지 않고, 현재 턴의 정확한 프로필은 다음 단계에서 적용합니다.

## Drive 계약

각 작업은 Drive Run 폴더에 필요한 문서를 고정 ID로 생성합니다.

- 요구사항 문서: 원본 사용자 요구와 작업 입력의 기준점입니다.
- 결과 문서: 각 logical turn이 정확히 소유하는 결과 JSON을 기록합니다.
- Handoff 문서: 각 logical turn 종료 시 Run 폴더의 `Handoff/` 아래에 새 checkpoint 문서를 만들고, Result의 top-level `handoff_document_id`로 연결합니다.
- 첨부파일: Run 폴더 아래에 업로드하고 원본 content URI 권한은 필요한 기간만 유지합니다.

결과 판정은 파일 제목이나 새 파일 탐색 순서가 아니라 고정된 결과 문서 ID와 strict result identity를 사용합니다. V1 `TURN_COMPLETED`, Drive cursor, title signal document는 V3 실행 계약이 아닙니다.

앱은 Result의 parse 가능한 JSON, 현재 task/turn/result-document/event identity와 boolean `committed`를 완료 무결성 hard gate로 검사합니다. 상세 인계는 Result에 중복 저장하지 않고 `handoff_document_id`가 가리키는 별도 Handoff 문서에 둡니다. `phase_completed`, routing, 완료 근거와 같은 semantic checkpoint의 형식·충분성은 AI가 canonical 운영문서와 실제 권위 상태를 읽어 판단합니다. 다음 실행에 필요하지 않은 정보 누락은 Repair를 만들지 않으며, 앱의 기존 정보와 정상 Result 정보로 후속 실행을 생성할 수 있으면 그대로 진행합니다.

후속 실행에 필요한 profile/model/reasoning 누락, 유효하지 않은 조합, Task mode 충돌, 서로 모순되는 routing 정보 때문에 후속 실행을 생성할 수 없으면 새 RESULT_REPAIR 턴으로 전환합니다. `REPAIR_PROBLEMS`에 확인된 필드와 누락·잘못된 값·모순 구분을 함께 전달합니다. 기존 committed Result는 수정하지 않고 `REPAIR_TARGET_DOCUMENT_ID`로 전달하며, 새 REPAIR Result를 작성한 뒤 그 문서에서 이어갑니다. REPAIR 대화방은 이전 턴의 실제 실행 config를 그대로 사용하므로 손상된 다음 profile에 의존하지 않습니다. SQLite/ledger·사용자 입력 저장·앱 내부 상태 오류는 Result Repair로 분류하지 않습니다.

SelfRun Drive 3.3.1부터 committed Result가 수락되면 successor 전환 상태와 전역 deadline을 원장에 함께 고정합니다. 전환이 지연되거나 프로세스가 재시작되거나 stale watchdog이 깨워도 저장된 successor 상태와 routing을 기준으로 같은 후속 턴을 중복 없이 복구하며, 이미 생성·전송된 successor는 다시 만들지 않습니다.

사용자 STOP 후 재개는 Drive의 기존 Result를 먼저 읽습니다. committed 결과는 후속 실행·완료·사용자 개입·병렬 Merge 경로로 반영하고, 미확정 결과는 같은 논리 턴과 문서 identity를 유지한 채 새 request로 준비부터 다시 시작합니다. 완료 branch와 기존 이력은 보존합니다. Drive 읽기 실패나 identity 불일치는 중지 상태를 유지하며, 전송 직전 재확인에서 늦게 확정된 결과를 발견하면 재전송하지 않습니다. PAUSE/RESUME의 기존 동작은 유지합니다.

## ChatGPT 실행

CHAT과 WORK 두 모드를 지원합니다. 앱은 사용자 선택 profile을 적용한 후 App-private WebView에서 요청을 제출하고 response protocol을 관측합니다. `TurnProtocolLogBridge`는 V3 WebAdapter가 소유한 WebView 이벤트만 수용하며 V3가 아닌 protocol ownership은 거부합니다.

정상 대기 중에는 짧은 주기 DOM polling을 사용하지 않습니다. UI 조작은 제출·프로필 적용·필요한 복구 구간으로 제한하고, 대기 상태는 protocol callback과 제한된 reconciliation으로 진행합니다.

## Result 대기 작업 모드

현재 정식 빌드는 `ON_DEVICE`와 `SERVER` 두 Result 대기 경로를 모두 제공합니다. `ON_DEVICE`가 기본·권장값이며 새 설치, 저장값 누락·손상·알 수 없는 값은 ON_DEVICE로 해석합니다. 3.2.6에서 저장된 `SERVER` 값은 최초 실행에서 다른 설정·Drive binding·원장을 지우지 않고 1회 ON_DEVICE로 전환되지만, 이후 설정 화면에서 SERVER를 다시 수동 선택할 수 있습니다.

- `ON_DEVICE`: 기존 `resultPollMs()` 기반 Result polling이 기본 경로입니다. PENDING Result는 유지하고, 현재 task/turn/result-document identity가 일치하는 `committed:true` Result만 소비하여 다음 논리 턴으로 진행합니다.
- `SERVER`: 일반 정식 빌드에서 활성화된 선택 경로이며 설정 화면에서 `서버를 통해 실행`으로 선택할 수 있습니다. 서버 watch, FCM, ACK/outbox와 recovery worker를 사용하되 ON_DEVICE의 기본·권장 지위는 유지합니다.
- 개발용 SERVER 전용 검증은 필요할 때 `-PselfRunServerChecks=true`로 명시적으로 실행합니다. 이 옵션은 일반 candidate/core CI의 기본값이 아닙니다.

Firebase public configuration과 Gateway endpoint는 SERVER 실행·검증 경로에서 사용하며 환경변수 또는 Gradle property로 주입합니다. 일반 ON_DEVICE core compile/test/build는 이 값들에 의존하지 않습니다.

```text
SELFRUN_FIREBASE_API_KEY
SELFRUN_FIREBASE_APPLICATION_ID
SELFRUN_FIREBASE_PROJECT_ID
SELFRUN_FIREBASE_SENDER_ID
SELFRUN_PUSH_GATEWAY_URL
```

Vercel `selfrun-command-bridge`의 server-side `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`는 SERVER 실행 경로의 Firebase Cloud Messaging 발송에 사용하는 서버 자격증명이며 Git이나 APK에 포함하지 않습니다.

## 앱 화면

하단 메뉴는 실행 · 기록 · 설정으로 구성됩니다.

- 실행: 현재 Task, mode/profile, V3 phase, 추가 지시, 일시정지/재개/중지, 로그를 표시합니다.
- 기록: 완료·중단된 작업의 V3 projection과 health 진단을 표시합니다.
- 설정: Drive base folder, profile registry, Web UI calibration, Result 대기 작업 모드를 관리합니다.

추가 지시는 `runId + text + revision`으로 저장하고 V3가 실제로 소비한 revision만 지웁니다. 구형 continuation lock/retry 상태는 사용하지 않습니다.

## 빌드와 검증

Candidate 검증은 `.github/workflows/build-selfrun-v3-candidate.yml`에서 수행합니다.

1. `command-bridge` Node dependency 설치, test, TypeScript typecheck
2. V3-only static policy와 전체 test-source compile
3. JVM regression suite
4. TEST target + instrumentation APK build
5. 고정 TEST 인증서 서명 검증
6. 현재 지원하는 정식 baseline과 TEST 후보의 동시 설치/업데이트 검증
7. V3 runtime instrumentation

개발 TEST application ID는 `com.shaterguy.chatgptselfrun.drive.test`, 정식 application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. TEST와 정식판은 별도 UID로 동시 설치됩니다.

정식 릴리즈는 해당 버전의 release workflow에서 정확한 source/version identity, 정식 업데이트 계보, TEST 병행 설치와 필요한 런타임 검증을 확인합니다. dev·rc·stable 채널과 승격 규칙의 현재 원본은 [`docs/RELEASE_CHANNELS.md`](docs/RELEASE_CHANNELS.md)입니다.
