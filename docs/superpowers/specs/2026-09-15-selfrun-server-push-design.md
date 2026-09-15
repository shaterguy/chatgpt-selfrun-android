# SelfRun Server Push Mode Design

## Goal

SelfRun Drive 3.2.5-dev2를 기준으로 결과 문서 대기 방식을 `SERVER`와 `ON_DEVICE` 중 선택할 수 있게 한다. 기본값은 `SERVER`다. SERVER 모드는 Google Drive `files.watch` 이벤트를 Vercel Push Gateway가 받아 FCM으로 정확한 앱 설치 인스턴스를 깨우고, Android가 결과 문서를 1회 확인하도록 한다. 푸시 또는 네트워크 손실로 작업이 무기한 정지하지 않도록 2단계 ACK, 서버 재전송, 기기 로컬 recovery watchdog을 함께 둔다.

## Baseline

- Repository: `shaterguy/chatgpt-selfrun-android`
- Baseline branch: `v3.2.5-dev2`
- Baseline SHA: `0a8bf5be9fdeb6dacab908d81aa17930c6e42251`
- Implementation branch: `v3.2.5-dev3`
- Stable package: `com.shaterguy.chatgptselfrun.drive`
- TEST package: `com.shaterguy.chatgptselfrun.drive.test`
- Vercel project: `selfrun-command-bridge`
- Vercel Root Directory: `command-bridge`

## User-facing behavior

설정의 실행 영역에 `작업 모드` 항목을 추가한다.

- `서버를 통해 실행` → `SERVER`
- `온디바이스` → `ON_DEVICE`
- 최초 설치 및 기존 설치에서 저장값이 없거나 손상됐으면 `SERVER`

ON_DEVICE는 기존 Result polling 의미를 그대로 유지한다. SERVER는 정상 경로에서 짧은 Result polling을 사용하지 않고 Push Gateway 이벤트로 Result read를 실행한다. 다만 서버/FCM 전체 경로가 실패해도 영구 정지하지 않도록 15분 주기의 로컬 recovery watchdog을 둔다.

## Installation identity and routing

라우팅의 권위 단위는 물리 기기가 아니라 앱 설치 인스턴스다.

`applicationId + installationId + taskId + turnId + resultDocumentId`

- `applicationId`가 정식/TEST를 분리한다.
- `installationId`는 앱 데이터 영역에 최초 1회 생성하는 128-bit 이상 난수 UUID이며 재설치/데이터 초기화 시 새로 생성된다.
- `taskId`, `turnId`, `resultDocumentId`는 현재 SelfRun ledger와 반드시 일치해야 한다.
- FCM registration token은 전송 주소일 뿐 논리 identity가 아니다. token 회전 시 최신 token을 다음 watch 등록에서 사용하며 현재 waiting turn은 로컬 watchdog으로 복구 가능하다.

푸시 payload를 받은 앱은 위 identity를 로컬 ledger와 대조한다. 하나라도 다르면 `STALE_PUSH`로 간주하고 Result read를 실행하지 않는다.

## SERVER data flow

1. 앱이 Result document를 준비하고 ChatGPT dispatch를 확정한다.
2. 앱이 Firebase registration token과 installation identity를 확보한다.
3. 앱이 Vercel `/api/watch/register`에 installation/task/turn/document 정보와 FCM token을 등록한다.
4. Gateway는 고엔트로피 `watchKey`, `channelId`를 만들고 durable watch workflow를 시작한다.
5. 앱은 자기 Drive OAuth access token으로 `files.watch(resultDocumentId)`를 등록한다. webhook address는 Vercel `/api/drive/webhook`, channel token은 `watchKey`다. Drive OAuth token이나 Result 문서 본문은 서버에 전달하지 않는다.
6. Drive가 파일 변경을 webhook으로 통지하면 Gateway가 channel identity를 검증하고 delivery workflow를 시작한다.
7. Gateway가 FCM high-priority data message를 해당 registration token 하나에 보낸다.
8. `FirebaseMessagingService`가 실제 수신한 즉시 `/api/push/ack`에 `RECEIVED` ACK를 보낸다. 실패하면 로컬 ACK outbox에 남긴다.
9. 앱은 ledger identity를 검증한 뒤 `SelfRunService`에 push result action을 전달한다.
10. coordinator가 해당 Result document를 1회 읽는다.
11. read 시도 및 현재 turn 처리 경계가 확보되면 `/api/push/ack`에 `PROCESSED` ACK를 보낸다. 실패하면 outbox에 남긴다.
12. Gateway는 `PROCESSED`를 받으면 event를 종료한다.

## ACK and retry state machine

서버 이벤트 상태 의미:

`PENDING → SENT → RECEIVED → PROCESSED`

- FCM API가 message name을 반환한 것은 `SENT`일 뿐 device delivery 증거가 아니다.
- `RECEIVED`는 `FirebaseMessagingService.onMessageReceived()`가 실행된 증거다.
- `PROCESSED`는 앱이 event identity를 검증하고 Result read 경로를 실제 실행한 증거다.
- 동일 `eventId`는 멱등하다. 중복 FCM은 이미 처리된 event이면 ACK만 다시 보낸다.
- ACK 대기 재전송 backoff는 `15s, 30s, 60s, 120s, 300s, 600s` 이후 600s를 상한으로 반복한다.
- `RECEIVED`가 없으면 동일 event를 재전송한다.
- `RECEIVED`는 있으나 `PROCESSED`가 없으면 동일 event를 다시 깨운다.
- FCM `collapse_key`는 installation별 현재 Result wake-up 하나만 남도록 고정된 SelfRun result key를 사용한다.

ACK 요청은 eventId와 full identity를 포함하며, 서버는 workflow가 보유한 identity와 일치하는 ACK만 소비한다.

## Durable Vercel gateway

`command-bridge/`를 새 Push Gateway로 사용한다.

- Runtime: Node.js/TypeScript
- Durable orchestration: Vercel Workflow (`workflow` package)
- No Drive content storage
- No Drive OAuth token storage
- Firebase Admin send는 Google service-account credentials를 Vercel environment variables로만 사용한다.
- Gateway secrets 또는 Firebase private key를 Git에 저장하지 않는다.

Endpoints:

- `GET /api/health`: gateway와 Firebase credential 구성 상태
- `POST /api/watch/register`: app에서 watch workflow 등록
- `POST /api/drive/webhook`: Google Drive push notification 수신
- `POST /api/push/ack`: Android RECEIVED/PROCESSED ACK 수신

Drive webhook routing은 고엔트로피 `watchKey` capability와 `X-Goog-Channel-ID`를 함께 검증한다. ACK routing은 고엔트로피 `eventId`를 capability로 사용한다.

## Android components

### Runtime setting

`SelfRun3RuntimeSettings`에 `KEY_WORK_MODE`와 `SERVER`/`ON_DEVICE`를 추가한다. `SERVER`가 default다.

### Installation identity

새 `SelfRunInstallationIdentity`가 application-private SharedPreferences에 UUID를 영속화한다. package identity는 `BuildConfig.APPLICATION_ID`를 사용한다.

### Gateway client

새 `SelfRunPushGatewayClient`가 HTTPS-only로 register/ack를 수행한다. redirect를 자동 추적하지 않고, bounded response, connect/read timeout, no-cache를 적용한다.

### Firebase receiver

새 `SelfRunFirebaseMessagingService`는 data payload를 검증하고 RECEIVED ACK를 outbox에 넣은 뒤 `SelfRunService`를 깨운다. stale identity는 실행하지 않는다.

### ACK outbox

`SelfRunPushAckOutbox`는 RECEIVED/PROCESSED ACK를 app-private persistence에 보존하고 idempotent하게 flush한다. 프로세스 종료 또는 네트워크 단절 뒤에도 재전송할 수 있다.

### Local recovery watchdog

`SelfRunServerRecoveryWorker`는 SERVER 모드에서 Result를 기다리는 active run이 있을 때 15분 간격으로 recovery action을 요청한다. 이 worker는 정상 감지 수단이 아니라 서버/FCM 전체 장애의 최종 safety net이다. ON_DEVICE에서는 기존 poll이 정상 경로이므로 이 worker를 사용하지 않는다.

### Coordinator integration

- ON_DEVICE: 현재 `nextResultPoll` + `runtimeSettings.resultPollMs()` 그대로 사용.
- SERVER: 정상 WAIT에서는 short poll을 예약하지 않는다.
- SERVER에서 push action 또는 recovery action이 도착하면 현재 ledger의 exact waiting execution을 확인하고 1회 `READ_RESULT`를 실행한다.
- push payload task/turn/document가 ledger와 다르면 무시한다.
- Result read 완료 경계에서 eventId가 있으면 PROCESSED ACK를 enqueue한다.
- mode 변경은 다음 waiting cycle부터 적용하며 현재 Drive/ledger identity를 바꾸지 않는다.

## Firebase configuration and safe fallback

Firebase public Android configuration과 server service-account credentials는 저장소에 비밀값으로 커밋하지 않는다.

- Android build는 Firebase 설정 값이 제공된 경우 SERVER push를 활성화한다.
- SERVER가 선택돼도 Firebase/Gateway가 미구성, registration 실패, watch 등록 실패이면 run을 hard-fail하지 않는다. 해당 waiting turn은 ON_DEVICE-compatible fallback polling을 사용하고 로그에 `V3_SERVER_PUSH_FALLBACK`을 남긴다.
- Vercel Firebase credentials가 없으면 health는 `configured:false`를 반환하고 push send는 명시적 503을 반환한다.

따라서 자격증명 배포가 늦어져도 기존 SelfRun 기능을 깨뜨리지 않는다.

## Security

- 서버는 Drive OAuth token, Result body, Requirement body를 받지 않는다.
- Firebase private key는 Vercel environment only다.
- registration/ACK/watch identity 값은 로그에 전체 secret을 남기지 않는다.
- watchKey/eventId는 cryptographically strong random capability다.
- webhook/ACK 입력 길이와 enum을 엄격히 검증한다.
- server endpoint는 HTTPS만 사용한다.
- Android는 cleartext를 계속 금지한다.

## Failure handling

- Device offline: FCM TTL + gateway retry + local watchdog.
- ACK upload offline: persisted outbox가 network 복구 후 재전송.
- Duplicate Drive webhook: same watch/event identity로 dedupe.
- Duplicate FCM: same eventId로 dedupe.
- Stale event after next turn: ledger mismatch로 무시, ACK는 stale/processed 형태로 서버가 종료할 수 있게 한다.
- Process death after RECEIVED: ACK outbox와 server retry가 다시 앱을 깨우며 local watchdog이 최종 복구한다.
- Gateway unavailable: app falls back to local polling for that waiting turn.
- Drive watch expiration: per-turn watch를 사용하고 local watchdog이 만료/유실을 보완한다.

## Versioning

구현 후보는 `3.2.5-dev3`, `versionCode 3025003`을 사용한다. 기존 정식/TEST applicationId와 signing lineage는 변경하지 않는다.

## Acceptance criteria

- AC-01: 작업 모드 UI가 SERVER/ON_DEVICE를 선택·영속화하고 default가 SERVER다.
- AC-02: ON_DEVICE는 3.2.5-dev2의 Result polling 동작을 보존한다.
- AC-03: SERVER는 Result wait 정상 경로에서 short polling을 제거하고 Drive push로 Result read를 시작한다.
- AC-04: phone/tablet, stable/TEST 설치가 installation identity로 격리된다.
- AC-05: task/turn/resultDocument identity가 일치하지 않는 push는 Result read를 실행하지 않는다.
- AC-06: device는 RECEIVED 및 PROCESSED ACK를 별도로 보내고 ACK 실패는 영속 outbox에서 재전송된다.
- AC-07: gateway는 PROCESSED 전까지 bounded backoff로 재전송하며 duplicate event는 멱등 처리한다.
- AC-08: SERVER push 전체 경로 실패 시 15분 local watchdog 또는 fallback poll로 run이 무기한 정지하지 않는다.
- AC-09: 서버는 Drive OAuth token/문서 본문을 저장하거나 읽지 않는다.
- AC-10: Firebase/Vercel secret이 저장소/APK source/log에 포함되지 않는다.
- AC-11: 기존 SelfRun ledger, repair, parallel/merge, user-intervention, stable/TEST co-install 계약이 비회귀한다.
- AC-12: Vercel `selfrun-command-bridge`가 `command-bridge` root에서 배포되고 health endpoint가 응답한다.
