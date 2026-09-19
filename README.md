# SelfRun 3

SelfRun 3는 Android 앱 안에서 ChatGPT 대화와 Google Drive 결과물을 하나의 논리 작업으로 관리하는 ledger 기반 실행기입니다. 현재 활성 개발 계보는 V3 하나뿐이며, V1/V2 실행 상태머신·Drive title signal·rollover·legacy migration을 런타임 호환 경로로 유지하지 않습니다.

현재 정식 승격 후보는 SelfRun Drive 3.2.9 / versionCode 3030000이며, 검증된 3.2.9-dev2 기능 상태를 기준으로 합니다.

## 디버그 로그와 턴별 바로가기

- 대화 주소와 제출이 확인되면 Result 대기 전에 작업 폴더의 단일 `<TASK_ID>-debug-log` Google Doc 갱신을 요청합니다. 이후 턴도 같은 문서를 갱신합니다.
- DONE, 일시정지, 중지, 사용자 개입 대기에도 마지막 이벤트를 포함한 갱신을 요청합니다. 로그 인증·네트워크·저장 실패는 실행을 멈추거나 인증 창을 열지 않으며, 다음 요청에서 누적 내용을 다시 보냅니다.
- 전송 요청과 누적 로그는 앱 전용 저장소에 보존하며 서비스 종료 후에도 보조 작업이 처리합니다. 업로드 스레드와 실행 원장은 분리됩니다. 이 버전에서 기록한 정제 로그는 기존 화면용 로그의 1 MiB 회전과 무관하게 누적됩니다. 이전 버전에서 이미 잘린 기록은 복원하지 않습니다.
- 대화 이력의 작은 `대화 열기`와 `작업문서` 버튼은 각 행의 대화와 Result 문서만 엽니다. 문서 ID가 없는 이전 행은 작업문서 버튼을 비활성화합니다.
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

고정 phase·checkpoint·Result commit·Branch/Merge/Repair/User Action 의미론은 `SELF_RUN_SKILL_DOCUMENT_ID`의 최신 전체 본문이 유일한 원본입니다. 최초 요구사항과 초기 Result identity는 각각 지정된 Drive 문서에서 읽으므로 프롬프트에 다시 넣지 않습니다. CHAT 프롬프트에는 profile 후보목록을 삽입하지 않으며 다음 CHAT 실행은 직전 Result의 `next_execution.profile` 또는 `next_profile`에 기록된 model/reasoning을 원본으로 사용합니다. ProfileRegistry는 그 선택값을 실제 request profile로 검증·변환합니다. WORK/HYBRID 프롬프트의 WORK 후보는 `PROFILE_REGISTRY_WORK` compact 목록으로 유지합니다.

## Drive 계약

각 작업은 Drive Run 폴더에 필요한 문서를 고정 ID로 생성합니다.

- 요구사항 문서: 원본 사용자 요구와 작업 입력의 기준점입니다.
- 결과 문서: 각 logical turn이 정확히 소유하는 결과 JSON을 기록합니다.
- 첨부파일: Run 폴더 아래에 업로드하고 원본 content URI 권한은 필요한 기간만 유지합니다.

결과 판정은 파일 제목이나 새 파일 탐색 순서가 아니라 고정된 결과 문서 ID와 strict result identity를 사용합니다. V1 `TURN_COMPLETED`, Drive cursor, title signal document는 V3 실행 계약이 아닙니다.

앱은 Result의 parse 가능한 JSON, 현재 task/turn/result-document/event identity와 boolean `committed`만 완료 무결성 hard gate로 검사합니다. `handoff`, `phase_completed`, 완료 근거와 같은 semantic checkpoint의 형식·충분성은 AI가 canonical 운영문서와 실제 권위 상태를 읽어 판단하며, semantic 필드 누락·별칭·shape drift만으로 committed Result를 거부하거나 Repair를 만들지 않습니다. 다음 실행 routing 힌트가 앱이 안전하게 실행할 수 없는 형태이면 Result 자체를 폐기하지 않고 현재 phase/profile의 SERIAL 경로로 폴백하여 다음 AI가 복구합니다.

## ChatGPT 실행

CHAT과 WORK 두 모드를 지원합니다. 앱은 사용자 선택 profile을 적용한 후 App-private WebView에서 요청을 제출하고 response protocol을 관측합니다. `TurnProtocolLogBridge`는 V3 WebAdapter가 소유한 WebView 이벤트만 수용하며 V3가 아닌 protocol ownership은 거부합니다.

정상 대기 중에는 짧은 주기 DOM polling을 사용하지 않습니다. UI 조작은 제출·프로필 적용·필요한 복구 구간으로 제한하고, 대기 상태는 protocol callback과 제한된 reconciliation으로 진행합니다.

## Result 대기 작업 모드

SelfRun Drive 3.2.7-dev1부터 일반 빌드의 정상 경로는 `ON_DEVICE`입니다. 새 설치, 저장값 누락·손상·알 수 없는 값은 모두 ON_DEVICE로 해석하며, 3.2.6에서 저장된 `SERVER` 값은 최초 실행에서 다른 설정·Drive binding·원장을 지우지 않고 1회 ON_DEVICE로 전환합니다.

- `ON_DEVICE`: 기존 `resultPollMs()` 기반 Result polling이 정상 경로입니다. PENDING Result는 유지하고, 현재 task/turn/result-document identity가 일치하는 `committed:true` Result만 소비하여 다음 논리 턴으로 진행합니다.
- `SERVER`: 구현은 호환성과 선택형 검증을 위해 dormant 상태로 남아 있지만 일반 빌드에서는 비활성화되며 설정 화면에서도 선택하지 않습니다. 서버 watch, FCM, ACK/outbox, recovery worker는 일반 런타임의 필수 경로가 아닙니다.
- 선택형 SERVER 검증이 필요한 개발자는 명시적으로 `-PselfRunServerChecks=true`를 사용합니다. 이 옵션은 일반 candidate/core CI의 기본값이 아닙니다.

Firebase public configuration과 Gateway endpoint는 선택형 SERVER 검사에만 필요하며 환경변수 또는 Gradle property로 주입합니다. 일반 ON_DEVICE 빌드는 이 값들이 비어 있어도 core compile/test/build가 가능해야 합니다.

```text
SELFRUN_FIREBASE_API_KEY
SELFRUN_FIREBASE_APPLICATION_ID
SELFRUN_FIREBASE_PROJECT_ID
SELFRUN_FIREBASE_SENDER_ID
SELFRUN_PUSH_GATEWAY_URL
```

Vercel `selfrun-command-bridge`의 server-side `FIREBASE_PROJECT_ID`, `FIREBASE_CLIENT_EMAIL`, `FIREBASE_PRIVATE_KEY`는 선택형 SERVER 검증용이며 Git이나 APK에 포함하지 않습니다.

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
6. 정식 3.0.0과 TEST 후보 동시 설치/업데이트 검증
7. V3 runtime instrumentation

개발 TEST application ID는 `com.shaterguy.chatgptselfrun.drive.test`, 정식 application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. TEST와 정식판은 별도 UID로 동시 설치됩니다.
