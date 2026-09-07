# SelfRun 3.0.0 구현 체크포인트

기록일: 2026-09-07 KST
상태: SOURCE_CHECKPOINT_ONLY / IMPLEMENTATION_INCOMPLETE / NO_APK
대상 저장소: shaterguy/chatgpt-selfrun-android
개발 브랜치: selfrun-v3/v3.0.0-dev1
정식 기준선: drive-v2.3.2 / 9a567558d6c81a1e9cd4c9f267b0e5171df3dec7
첫 개발 커밋: 622708a54762866a46463fda7bd8ade35e603317
이번 체크포인트의 사전 저장 소스 tree: 23abbab0d26edc35f8e9a350ad29cef6f70f3ab0

## 1. 사용자 요구와 범위

사용자는 기존 SelfRun의 구조적 재작성과 3.0.0 완성 APK를 요청했다. AI 유지보수, 자체 코드 수정, 자동 패치 에이전트는 명시적으로 제외했다. 배터리 소모와 발열은 기존 실사용 수준을 넘지 않아야 한다. 현재 파일은 완성본이나 릴리스 보고서가 아니다.

작업 의미와 외부 연결을 분리하는 신규 엔진, 트랜잭션 원장, 작업/논리 턴/물리 요청 식별자, 정확한 턴별 결과 문서, 교체 가능한 연결 어댑터를 구현 대상으로 삼았다. 기존 로그인, 프로필 캡처, CHAT/WORK, 일반/Project 대화, 첨부, 기록, 중지와 재개는 보존 요구사항이다. 별도 서버나 유료 API 전환은 범위에 없다.

## 2. 저장된 소스 초안

app/src/main/java/com/shaterguy/chatgptselfrun/ 아래의 다음 파일은 원격 GitHub에 작성된 초안이다. 기존 앱에서 호출되거나 테스트를 통과했다는 뜻이 아니다.

- SelfRun3Engine.java: 순수 상태 전이, 작업/턴/요청 귀속, 전송 시도와 접수/종료/논리 결과 분리, 결과 JSON 식별자 검증, 명시적 정지와 복구 상태를 정의한다.
- SelfRun3Ledger.java: SQLite의 작업 스냅샷, 이벤트 중복 수신 기록, 턴 결과를 같은 트랜잭션으로 기록하는 초안이다.
- SelfRun3PowerPolicy.java: 정상 대기의 폴링 0, 제한된 화면 조작과 예외 복구 예산을 정의한다. 실제 전력 보존을 검증한 결과는 아니다.
- SelfRun3Protocol.java: 구형 문서 제목 신호와 분리된 V3 결과 본문 계약을 정의한다. AI 유지보수 기능이 아니다.
- SelfRun3WebAdapter.java: 기존 입력/프로필/응답 관측 프리미티브를 신규 엔진의 연결 포트로 사용하려는 초안이다. 기존 서비스와 로그 브리지에 연결되지 않았다.
- SelfRun3DriveAdapter.java: 턴별로 고정된 기존 문서 ID를 읽고 원문/첨부를 준비하는 초안이다. 필요한 기존 API 보조 메서드가 아직 없어 현재 소스는 통합 빌드 가능한 상태가 아니다.

추적 기준과 수락 조건은 SELFRUN_3_WORK_PACKET.md에 있다.

## 3. 실제 중단 지점

SelfRun3Runtime.java를 GitHub.create_tree로 저장하는 호출에서 도구가 다음 메시지를 반환했다.

요청의 보안 상태를 결정하지 못해 이 도구 요청은 OpenAI에 의해 차단되었습니다.

호출 결과에는 성공 tree SHA가 없었다. 개발 브랜치의 현재 ref를 읽어 기존 622708a 커밋이 그대로임을 확인한 뒤 같은 요청을 한 번 동일하게 재시도했으나 같은 차단 메시지가 반환되었다. 해당 런타임 파일은 저장되지 않았다. 차단된 요청을 다른 경로나 분할된 코드로 우회하지 않았다. 이 체크포인트에는 차단 이전에 성공적으로 저장된 소스만 포함한다.

구체적인 차단 원인, 허용 조건이나 사용자 조치로 해결 가능한 방법은 도구 응답에서 확인되지 않았다. 사용자 동의나 GitHub 권한 부족으로 단정하지 않는다.

## 4. 미완료 구현과 정적 검토 사항

1. SelfRun3Runtime.java, SelfRunService의 V3 분기, SelfRunStore의 엔진 버전과 UI 투영, 새 작업 시작 화면 연결, TurnProtocolLogBridge의 V3 콜백 연결은 반영되지 않았다.
2. SelfRun3DriveAdapter가 참조하는 DriveApiClient.findV3Document는 아직 구현되지 않았다. 폴더/이름/MIME/휴지통/중복 후보를 엄격하게 확인하는 조회 계약과 기존 메서드 시그니처 대조가 필요하다.
3. 사용자 추가 입력의 revision 기반 수신확인과 마지막 DONE 도착 시 새 미처리 입력을 보존하는 전이가 필요하다. 새 입력을 무시한 채 DONE으로 종료하지 않아야 한다.
4. 프로세스 재생성, 전송 직전/직후 종료, 늦은 콜백, 중복 결과, 중지와 콜백 경합의 테스트가 없다. 초기 로드 전 일시정지와 미완료 브라우저 검사 콜백의 종료 조건도 확정해야 한다.
5. 결과 JSON 파서는 Android JSONTokener의 관대한 구문 수용을 엄격한 JSON 검증으로 오인하면 안 된다. JVM과 Android의 파서 차이 및 중복 키/형 변환/후행 입력을 실제 테스트해야 한다.
6. 원장 손상 시 기본 SQLite 오류 처리에 의존하지 않고 데이터 보존과 자동 전송 차단을 확인해야 한다. 현재 원장에는 별도 DatabaseErrorHandler가 없다.
7. 물리 요청 실패를 논리 작업 실패로 간주하거나 확인되지 않은 요청을 재전송하지 않아야 한다. 기존 대화방 확인과 결과 복구 요청의 프로필 정보 완전성도 검증해야 한다.
8. 구형 진행 중 작업의 V2 계약 유지, 기록 재시작과 신규 V3 실행의 분리, 기존 첨부/프로필/계정/사용자 입력 보존은 아직 시험하지 않았다.
9. app/build.gradle, 설치 버전과 CI workflow는 변경하지 않았다. 현재 앱 메타데이터는 기준선 2.3.2 그대로이며, 이 소스를 3.0.0 설치본으로 표시해서는 안 된다.

## 5. 확인한 플랫폼 근거

다음은 구현 검토에 사용한 공식 자료이며 테스트 결과를 대신하지 않는다.

- Android JSONTokener는 주석과 작은따옴표 등을 허용하는 lenient parser이고 nextValue는 JSONException을 던질 수 있다. 엄격한 결과 계약에는 별도 검증과 Android 런타임 시험이 필요하다.
  https://developer.android.com/reference/org/json/JSONTokener
- SQLiteOpenHelper는 DatabaseErrorHandler를 지정하는 생성자를 제공하며, 데이터베이스 열기와 이전은 메인 스레드에서 수행하지 않아야 한다. 파괴적 손상 처리를 피하는 구현이 필요하다.
  https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper
  https://developer.android.com/reference/android/database/DefaultDatabaseErrorHandler
- getNoBackupFilesDir는 자동 원격 백업 제외 경로를 제공한다. 이 선택은 데이터 암호화나 손상 방지를 자동 보장하지 않는다.
  https://developer.android.com/reference/android/content/Context.html
- SharedPreferences.Editor의 원자성은 해당 preferences 편집에 관한 것이다. SQLite와 별도의 입력 수신확인을 하나의 트랜잭션으로 착각하지 않아야 한다.
  https://developer.android.com/reference/android/content/SharedPreferences.Editor
- Drive 파일 검색은 q, exact name, parents, MIME 등으로 제한할 수 있다. 결과 상태는 검색 순서가 아니라 고정 문서 ID와 본문 식별자로 판정해야 한다.
  https://developers.google.com/workspace/drive/api/guides/search-files
- Docs batchUpdate 내부 요청들은 원자적으로 적용되며 requiredRevisionId가 현재 revision과 다르면 요청이 처리되지 않는다. 별도 Drive 파일 생성까지 같은 트랜잭션이라는 의미는 아니다.
  https://developers.google.com/workspace/docs/api/reference/rest/v1/documents/batchUpdate

## 6. 검증·전달 상태

STATIC_PREFLIGHT=NOT_RUN
FAST_TEST=NOT_RUN
ARTIFACT_BUILD=NOT_RUN
ANDROID_RUNTIME_TEST=NOT_RUN
SIGNED_APK=NOT_CREATED
DIRECT_APK_DELIVERY=NOT_AVAILABLE
PHYSICAL_POWER_THERMAL=NOT_MEASURED
USER_READY=NOT_VERIFIED
RELEASE=NOT_PERFORMED

저장된 원격 소스와 파일 내용의 readback만 완료 범위다. 설치 가능한 3.0.0 APK가 없으며 현재 상태로 CI 통과나 사용자 기능 동등성을 주장하지 않는다. 정식 main/tag/Release를 변경하지 않았고 기존 2.3.2 APK를 새 버전으로 재표시하지 않았다.

ACTION_EXECUTION_DECISION=NO_ACTION. 기존 workflow의 selfrun-drive 브랜치 필터와 다른 selfrun-v3 개발선이며, 이번 체크포인트에서는 workflow 변경, PR 생성, 수동 Actions 실행이나 태그를 만들지 않는다.

REUSABLE_LESSON_CANDIDATES=NONE. 현재 실행의 미완료 통합 및 원격 쓰기 차단은 이 기록에 보존한다. 아직 검증된 범용 예방 행동의 근거가 없으므로 예방 규칙 원장을 추가 수정하거나 최종 VERIFICATION PASS로 기록하지 않는다.
