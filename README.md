# SelfRun 3

SelfRun 3는 Android 앱 안에서 ChatGPT 대화와 Google Drive 결과물을 하나의 논리 작업으로 관리하는 ledger 기반 실행기입니다. 현재 활성 개발 계보는 V3 하나뿐이며, V1/V2 실행 상태머신·Drive title signal·rollover·legacy migration을 런타임 호환 경로로 유지하지 않습니다.

## 실행 구조

- `SelfRun3Engine`: Task → logical Turn → physical Request의 상태 전이를 정의합니다.
- `SelfRun3Ledger`: 작업 스냅샷, 이벤트, 턴 결과를 SQLite 원장에 기록합니다.
- `SelfRun3Coordinator`: 원장을 기준으로 Drive 준비, ChatGPT 전송, 응답 관측, 결과 reconciliation을 조정합니다.
- `SelfRun3DriveAdapter`: 요구사항·첨부·턴별 결과 문서를 고정 ID로 관리합니다.
- `SelfRun3WebAdapter`: 앱 전용 WebView에서 CHAT/WORK 프로필 적용, 제출, 응답 protocol 관측을 수행합니다.
- `SelfRunStore`: V3 원장의 사용자 화면 projection, Drive base binding, 첨부 URI 소유권만 보관합니다.

앱의 authoritative execution state는 `SelfRun3Ledger`에 있으며 `SelfRunStore`는 별도의 구형 실행 상태머신이 아닙니다.

## AI 계약

SelfRun 3 프롬프트는 매 턴 전체 계약을 반복하지 않고 compact envelope만 전송합니다.

```text
[SELF_RUN_V3 3.0.0]
SELF_RUN_SKILL_DOCUMENT_ID=1qPTSmJG8GpXMSyIGm6SIpgx6-LtWCBGVW3WUpoKj9fs
TASK_ID=...
TURN_ID=...
TURN=...
PHASE=...
MODE=...
RESULT_DOCUMENT_ID=...
REQUIREMENT_DOCUMENT_ID=...
PREVIOUS_RESULT_DOCUMENT_ID=...
FOLDER_ID=...
RECEIPT=[SELF_RUN_3_RESULT ...]
```

Canonical AI semantics는 위 Google Drive SKILL에 있으며 앱은 고정 계약 본문을 매 요청에 복제하지 않습니다. WORK 모드에서는 선택된 model/reasoning profile을 envelope에 추가합니다.

## Drive 계약

각 작업은 Drive Run 폴더에 필요한 문서를 고정 ID로 생성합니다.

- 요구사항 문서: 원본 사용자 요구와 작업 입력의 기준점입니다.
- 결과 문서: 각 logical turn이 정확히 소유하는 결과 JSON을 기록합니다.
- 첨부파일: Run 폴더 아래에 업로드하고 원본 content URI 권한은 필요한 기간만 유지합니다.

결과 판정은 파일 제목이나 새 파일 탐색 순서가 아니라 고정된 결과 문서 ID와 strict result identity를 사용합니다. V1 `TURN_COMPLETED`, Drive cursor, title signal document는 V3 실행 계약이 아닙니다.

## ChatGPT 실행

CHAT과 WORK 두 모드를 지원합니다. 앱은 사용자 선택 profile을 적용한 후 App-private WebView에서 요청을 제출하고 response protocol을 관측합니다. `TurnProtocolLogBridge`는 V3 WebAdapter가 소유한 WebView 이벤트만 수용하며 V3가 아닌 protocol ownership은 거부합니다.

정상 대기 중에는 짧은 주기 DOM polling을 사용하지 않습니다. UI 조작은 제출·프로필 적용·필요한 복구 구간으로 제한하고, 대기 상태는 protocol callback과 제한된 reconciliation으로 진행합니다.

## 앱 화면

하단 메뉴는 실행 · 기록 · 설정으로 구성됩니다.

- 실행: 현재 Task, mode/profile, V3 phase, 추가 지시, 일시정지/재개/중지, 로그를 표시합니다.
- 기록: 완료·중단된 작업의 V3 projection과 health 진단을 표시합니다.
- 설정: Drive base folder, profile registry, Web UI calibration 등 실행 인프라를 관리합니다.

추가 지시는 `runId + text + revision`으로 저장하고 V3가 실제로 소비한 revision만 지웁니다. 구형 continuation lock/retry 상태는 사용하지 않습니다.

## 빌드와 검증

Candidate 검증은 `.github/workflows/build-selfrun-v3-candidate.yml`에서 수행합니다.

1. V3-only static policy와 전체 test-source compile
2. JVM regression suite
3. TEST target + instrumentation APK build
4. 고정 TEST 인증서 서명 검증
5. 정식 3.0.0과 TEST 후보 동시 설치/업데이트 검증
6. V3 runtime instrumentation

개발 TEST application ID는 `com.shaterguy.chatgptselfrun.drive.test`, 정식 application ID는 `com.shaterguy.chatgptselfrun.drive`입니다. TEST와 정식판은 별도 UID로 동시 설치됩니다.

## 릴리스 계보

- 개발: `selfrun-v3/v<version>-devN`
- RC: `selfrun-v3/v<version>-rcN`
- 정식 태그: `drive-v<version>`
- 정식 기준 앱: SelfRun 3

활성 V3 소스에는 V1/V2 호환 실행기를 두지 않습니다. 과거 커밋·이미 발행된 태그·Release는 저장소 이력이며 현재 런타임 계약으로 사용하지 않습니다.

## Google Cloud

Android OAuth는 정식 package와 실제 서명 인증서 조합으로 등록합니다. Drive/Docs 접근은 `drive.file` 범위로 제한하며 client secret이나 OAuth access token을 소스·로그에 저장하지 않습니다.
