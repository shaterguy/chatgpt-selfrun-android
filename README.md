# SelfRun Drive

SelfRun 3는 Android 앱 안에서 ChatGPT 대화와 Google Drive 결과물을 하나의 논리 작업으로 관리하는 ledger 기반 실행기입니다. 작업 상태는 기존 V3 원장을 유지하며, V1/V2 실행 상태머신·Drive title signal·rollover·legacy migration을 런타임 호환 경로로 유지하지 않습니다.

현재 정식 버전은 SelfRun Drive 4.0.6 / versionCode 4002006입니다. 4.0.5의 기능을 유지하면서 정식 패키지로 배포됐던 4.0.4-dev2에서도 앱 삭제 없이 업데이트할 수 있도록 설치 버전 번호를 바로잡았습니다. 정식 applicationId와 서명 계보는 유지됩니다. 최신 정식 Release는 [drive-v4.0.6](https://github.com/shaterguy/chatgpt-selfrun-android/releases/tag/drive-v4.0.6)입니다.

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
- `SelfRun3Coordinator`: 원장을 기준으로 Drive 준비, ChatGPT 전송, 제출 확인, Vercel 완료 신호 수신과 결과 반영을 조정합니다.
- `SelfRun3DriveAdapter`: 요구사항·첨부·턴별 결과 문서를 고정 ID로 관리합니다.
- `SelfRun3WebAdapter`: 온디바이스 선택 시 앱 전용 WebView에서 CHAT/WORK 프로필 적용과 제출만 수행하고, 제출 확인과 대화 주소 저장 후 WebView를 파괴합니다. 응답을 읽거나 감시하지 않습니다.
- `SelfRunStore`: V3 원장의 사용자 화면 projection, Drive base binding, 첨부 URI 소유권만 보관합니다.

앱의 authoritative execution state는 `SelfRun3Ledger`에 있으며 `SelfRunStore`는 별도의 구형 실행 상태머신이 아닙니다.

## AI 계약

SelfRun 3 프롬프트는 Google Drive의 canonical 운영문서를 복제하지 않고, 각 새 conversation에서만 결정되는 dynamic envelope를 전송합니다.

```text
TASK_ID=...
TURN_ID=...
REQUEST_ID=...
SELF_RUN_SKILL_DOCUMENT_ID=1gktKYJzz4zW_M2gbJ7OsodaTE1lkx-pUtuFBV5fucJo
RESULT_DOCUMENT_ID=...
REQUIREMENT_DOCUMENT_ID=...
PREVIOUS_RESULT_DOCUMENT_ID=...
```

`TURN_ID`와 `REQUEST_ID`는 의미론 중복 정보가 아니라 브라우저의 canonical POST 감시기가 현재 물리 전송을 정확한 턴·요청과 결부하는 상관관계 토큰이므로 프롬프트에 유지합니다. `TURN`, `PHASE`, `TASK_MODE`, `MODE`, `SIGNAL_TYPE`, `EXECUTION_PROFILE` 등 문서에서 복원 가능한 상태값은 보내지 않습니다.

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

CHAT과 WORK 두 모드를 지원합니다. ON_DEVICE는 v3 방식으로 휴대폰 WebView에서 사용자 선택 profile을 적용하고 요청을 제출합니다. outgoing canonical POST와 대화 주소만 확인하며, 저장이 끝나면 WebView를 파괴합니다. SERVER는 현재 v4 Drive-to-Termux 방식으로 제출하고 서버가 응답을 감시합니다. `TurnProtocolLogBridge`는 V3 WebAdapter가 소유한 WebView 이벤트만 수용하며 V3가 아닌 protocol ownership은 거부합니다.

온디바이스의 제출 후 응답 본문·완료 여부·화면 상태는 관찰하지 않습니다. 다음 턴은 새 WebView에서 새 요청을 제출합니다. 완료 신호 수신과 Result 무결성 검사는 WebView 없이 공통 경로에서 처리합니다.

## 제출 방식과 공통 완료 신호

개발 후보 `4.0.7-dev3`의 설정에서 `ON_DEVICE`와 `SERVER`는 제출 방식을 선택합니다. 기존 저장된 선택값과 기본 ON_DEVICE는 유지합니다.

- `ON_DEVICE`: 휴대폰 WebView에서 v3 방식으로 프롬프트를 제출하고 대화 주소를 확보·저장하면 WebView를 파괴합니다. 응답 감시는 하지 않습니다.
- `SERVER`: 현재 v4 서버가 Drive 요청을 받아 제출하고 응답을 감시합니다.
- 완료 신호는 두 방식 모두 현재 Vercel watch → FCM → 앱의 고정 Result 확인 경로를 사용합니다. 신호 누락·읽기 실패에 대한 기존 제한된 재확인·복구 정책은 유지합니다.
- 제출 방식은 새 요청을 준비할 때 원장에 저장합니다. 설정 변경과 프로세스 재시작이 이미 준비·제출 중인 요청을 다른 경로로 다시 보내지 않습니다. 업그레이드 전 준비된 요청은 기존 SERVER 경로를 유지합니다.
- 서버 경로를 포함한 전체 JVM 검증은 `-PselfRunServerChecks=true`로 실행합니다.

두 방식의 공통 완료 알림에 필요한 Firebase public configuration과 Gateway endpoint는 기존 환경변수 또는 Gradle property를 그대로 사용합니다. Vercel 자격증명·설정은 이 기능에서 변경하지 않습니다.

```text
SELFRUN_FIREBASE_API_KEY
SELFRUN_FIREBASE_APPLICATION_ID
SELFRUN_FIREBASE_PROJECT_ID
SELFRUN_FIREBASE_SENDER_ID
SELFRUN_PUSH_GATEWAY_URL
```

Vercel `selfrun-command-bridge`의 server-side `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`는 두 제출 방식의 공통 Firebase Cloud Messaging 발송에 사용하는 서버 자격증명이며 Git이나 APK에 포함하지 않습니다.

## 앱 화면

하단 메뉴는 실행 · 기록 · 설정으로 구성됩니다.

- 실행: 현재 Task, mode/profile, V3 phase, 추가 지시, 일시정지/재개/중지, 로그를 표시합니다.
- 기록: 완료·중단된 작업의 V3 projection과 health 진단을 표시합니다.
- 설정: Drive base folder, profile registry, Web UI calibration, 제출 방식을 관리합니다.

추가 지시는 `runId + text + revision`으로 저장하고 V3가 실제로 소비한 revision만 지웁니다. 구형 continuation lock/retry 상태는 사용하지 않습니다.

## 빌드와 검증

현재 후보 검증은 `.github/workflows/selfrun-dual-submission.yml`에서 수행합니다.

1. `command-bridge` Node dependency 설치, test, TypeScript typecheck
2. V3-only static policy와 전체 test-source compile
3. JVM regression suite
4. TEST target + instrumentation APK build
5. 고정 TEST 인증서 서명 검증
6. 현재 지원하는 정식 baseline과 TEST 후보의 동시 설치/업데이트 검증
7. V3 runtime instrumentation

개발 TEST application ID는 `com.shaterguy.chatgptselfrun.drive.test`, 정식 application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. TEST와 정식판은 별도 UID로 동시 설치됩니다.

정식 릴리즈는 해당 버전의 release workflow에서 정확한 source/version identity, 정식 업데이트 계보, TEST 병행 설치와 필요한 런타임 검증을 확인합니다. dev·rc·stable 채널과 승격 규칙의 현재 원본은 [`docs/RELEASE_CHANNELS.md`](docs/RELEASE_CHANNELS.md)입니다.
