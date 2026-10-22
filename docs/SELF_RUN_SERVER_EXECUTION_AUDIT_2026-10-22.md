# SelfRun 서버 실행 로직 전수조사 및 변경

기준 소스: `shaterguy/chatgpt-selfrun-android@c24edfefa30936cf2542656aae4f9043a6b0ea93` (`4.0.0-dev40`). 조사한 활성 서버는 Galaxy-Tab-S11-Ultra의 `bin/selfrun4-run`이 가리키는 `launch-dev40.sh`이며, 실제 APP는 `work/selfrun-server-v4.0.0-dev40-tgwo3z-release/source-checkout/termux-server`입니다. 수정·시험은 이 활성 checkout과 분리한 `work/selfrun-logic-20261022`에서 수행합니다. 대표 서버 후보는 `4.0.0-dev41`입니다. Android 앱·APK·정식 배포·AI phase/Result 작성 계약은 변경하지 않습니다.

## 실행 경로와 변경 범위

| 실제 진입점 | 연결 경로 | 조사 및 변경 결과 |
|---|---|---|
| HTTP `/v1/signals` | `server.mjs` → `SelfRunController.accept` → `DriveDispatchController` | START/NEXT는 동일 core로 진입합니다. DONE 및 COMPLETE/COMPLETED도 명시적 종료 제어로 전달합니다. |
| Drive CREATE/SEND | `DriveDispatchWatcher` → `ingestDurable` → `#advance` | CREATE_REQUESTED와 SEND_REQUESTED를 동일 전체 생성 흐름으로 통합했습니다. 동일 request의 후속 SEND와 재조회는 저장된 intent로 중복 입력하지 않습니다. |
| 앱 후속 대화 생성 | current control 갱신 → predecessor 폐기 → `#open(prepare)` | turn 번호에 따른 분기가 없습니다. 이전 opening의 종료 barrier와 실제 CLOSED/ABSENT를 기다린 뒤 새 탭을 만듭니다. |
| 프롬프트 입력 | `ChatGptBrowser.prepare` → `submitIntent` → server action/CDP | 프로필 설정·입력·실제 submit의 동일 primitive를 사용합니다. 최초 POST 후 receipt를 기다려 성공·실패를 추정하지 않습니다. URL은 해당 소유 탭에서 관측되는 즉시 기존 outbox로 앱에 게시합니다. |
| 응답 감시 | extension content snapshot 또는 CDP → `ChatGptBrowser.monitor` → `#observe` | 현재 응답의 유효 글자수 변화만 시계를 갱신합니다. 같은 길이의 내용·fingerprint·message ID·streaming·paused·버튼·오류 문구는 진행 판정에 사용하지 않습니다. 감소도 길이 변화입니다. |
| 10분 미변동 | server tick → `#quietCycle` → 현재 Result 최신 읽기 | 매 600000ms 미변동마다 현재 `result_document_id`를 읽습니다. 최신 추가지시에 따라 정확한 `status === USER_ACTION_REQUIRED`일 때 그 주기의 입력만 생략합니다. committed·DONE·CONTINUE 등의 의미를 판정하지 않습니다. 감시와 다음 10분 검사는 유지합니다. |
| Result 조회 불능 | `readUserIntervention` | 실제 I/O 또는 JSON/문서 identity를 확인하지 못하면 관측을 유지하며 30초 간격으로 읽기만 재시도합니다. 개입·완료 상태나 BLOCKED/error를 창작하지 않습니다. STOP은 AbortSignal로 읽기를 즉시 취소합니다. |
| 앱 STOP/DONE | 검증된 current control → 동기 Abort → session/target cleanup | epoch·identity 충돌 검증 직후 첫 await 전에 취소합니다. 다른 task는 건드리지 않습니다. 늦게 만들어진 탭도 종료 문맥으로 폐기합니다. 물리 close 실패를 ownership 폐기로 숨기지 않습니다. |
| 재시작·클러스터 | execution claims/current control → 정확한 기존 target 재연결 | 중복 입력 방지를 위한 실제 앱 권한·소유권·세대 검증은 유지합니다. 원격 control/claim을 읽지 못해도 이미 소유한 정확한 탭의 읽기 전용 관찰과 URL 게시를 유지합니다. 이는 새 턴의 대화 재사용 경로가 아닙니다. |
| 브라우저 bootstrap/extension | adapter → `ExtensionManager`/`ChromiumManager` → action session | 기존 paired owner·허용 origin·정확한 PID/startTime·target identity 검증은 유지합니다. 초기 WEB optimistic route의 30초 경과만으로 runtime을 revoke하던 조건은 삭제했습니다. |
| 탭 폐기 | `TerminalBrowserCleanup` → adapter close → inventory readback | extension도 실제 target 목록에서 사라졌음을 확인합니다. close 실패나 repurpose 상태를 CLOSED로 간주하지 않으며 pending disposal proof를 남깁니다. 신규 task 생성은 자기 predecessor의 미폐기 탭이 있으면 진행하지 않습니다. |
| idle 자동 적용 | `tools/apply-when-idle.mjs` | 현재 작업을 중단하지 않고 앱 current control·durable state·실제 소유 탭이 모두 비었을 때만 실행하는 별도 일회성 패치입니다. 검증과 실제 예약은 RELEASE 담당 증거를 사용합니다. |

## 제거한 판정과 남겨 둔 실제 실패

삭제한 live 실행 판단은 Result committed를 이유로 continue를 막는 게이트, receipt UNKNOWN/ABSENT를 통한 오류 확정과 초기 prompt 재전송, response fingerprint·ID·UI 상태를 통한 진행/정체 판정, recovery 단계별 VERIFY_RESULT/VERIFY_CURSOR/VERIFY_INPUT 게이트, integrity/history 불명확을 이유로 관찰을 멈추는 분기, old BLOCKED epoch에 머무는 분기입니다. `POST_UNCERTAIN`, `RECOVERY_NO_PROGRESS`, `POST_IDENTITY_UNCERTAIN`, `submission readback remains inconclusive` 같은 추정 오류는 새 executor에서 생성하지 않습니다. 과거 로그 migration도 unknown 전송 식별자만 보존하며 추정 오류를 새로 만들지 않습니다.

남긴 실패는 잘못된 입력/schema, 실제 파일·인증·네트워크 I/O 실패, 브라우저/target 연결 실패, 실제 composer/input/submit 조작 불가, 정확한 소유권 변경, 실제 이전 탭 폐기 불가입니다. 이 실패는 진행 의미를 해석한 결과가 아닙니다. 실제 submit을 호출했는지 불명확한 경우에는 중복 submit을 금지하고 같은 탭의 관찰을 계속합니다. 함수 `readResult`·`readSubmission` 및 구 lifecycle state enum은 기존 테스트/호환 API에 남아 있으나 live executor는 이를 호출하지 않습니다. 새 문서 읽기는 사용자 개입 예외 전용 `readUserIntervention`만 호출합니다.

## 동시성 및 검증 기준

AC-01은 CREATE/SEND/START/NEXT의 동일 prepare/submit/URL 경로와 중복 방지로 검증합니다. AC-02는 정확한 600000ms 경계·반복 주기·길이 감소·동일 길이 내용 변화·지속 개입·개입 해제·문서 I/O 실패·조회 중 길이 변화로 검증합니다. onActivity 진입 시 동기 progressVersion을 갱신하여 Result 읽기·프로필 설정·입력 준비가 async로 진행되는 동안 새 글자수가 들어오면 실제 recovery submit 직전 guard를 무효화합니다. 같은 millisecond에서도 길이/version을 함께 확인합니다.

AC-03은 이전 close의 Promise가 끝나기 전 후속 탭이 없음을 확인하고, late opening barrier는 저장 실패 때도 settle합니다. AC-04는 STOP/DONE during opening/read/submit과 잘못된 same-epoch STOP의 무효성을 검증합니다. AC-05와 NR-01은 현재 권한·클러스터 CAS·다른 task 격리·재시작 시 중복 전송 방지를 함께 확인합니다. REL-01은 업데이터의 별도 테스트와 실제 WAITING/APPLIED readback으로 판정합니다.

초기 영향 회귀 311개 중 291개가 통과했고, 실패 17개와 timeout 3개를 분리하여 수정했습니다. 같은 길이 fingerprint도 progress로 인정하던 기대, optimistic 30초 자동 revoke, 물리 close 실패 뒤 cleanup을 버리던 기대, receipt 응답에 종속되던 fixture tick, resume 호출횟수만으로 재연결을 세던 기대는 새 계약에 맞게 정합화했습니다. timeout은 monitor fixture의 0ms가 60초 기본값으로 바뀐 문제와 standby에서 물리 close 없이 extension cache-dispose 호출만 기다리던 오래된 시험이 원인이었습니다. 실제 기능 회귀였던 cold claim I/O 실패 시 URL 관찰 누락은 정확한 target metadata 관찰을 권한 조회와 분리하여 고쳤습니다. 테스트를 삭제하거나 통째로 skip하여 통과시키지 않았습니다.

최종 검증 범위·명령·종료 코드와 최종 source hash는 인계 패킷 및 독립 검증 결과를 따릅니다. 실제 운영 작업을 중단하거나 사용자 채널에 시험 프롬프트를 보내는 E2E는 수행하지 않습니다. 운영 적용 전에는 현재 작업이 계속 dev40으로 동작하며, updater가 APPLIED를 확인하기 전에는 dev41 운영 적용 완료라고 표현하지 않습니다.

## 최종 동결 검증

Galaxy-Tab-S11-Ultra의 분리 worktree에서 다음 검증을 수행했습니다. 최종 코드·테스트·업데이터와 문서 총 30개 파일은 로컬/원격 SHA-256이 일치했습니다. 검증 후에는 이 감사 문서의 검증 기록을 추가하고, git diff --check가 지적한 기존 수정 파일 19개의 끝 빈줄만 정규화했습니다. 실행 코드·테스트의 의미는 동결본과 동일하므로 기존 기능 검증 증거를 재사용합니다.

| 검증 | 결과 | 증거 |
|---|---|---|
| `npm run check` | exit 0 | `SELFLOGIC-BUILDER-FINAL-FREEZE-04` |
| 영향 범위 16개 테스트 파일 | 320 PASS, 0 FAIL, 0 SKIP, exit 0 | 동일 job의 `selfrun-logic-20261022-frozen4-regression.log` |
| idle 업데이터 | 14 PASS, 0 FAIL, 0 SKIP, exit 0 | `SELFLOGIC-20261022-IDLE-UPDATER-TEST-02` |
| 별도 검증자의 실행·취소·개입·소유권 시나리오 | 유효 시나리오 17개 PASS, blocking 0 | 독립 executor 15개와 실제 ExtensionManager inventory 2개 |
| 실제 Chromium localhost 검증 | exit 0, 실제 전송 1회, 응답 길이 > 0, 소유 탭 부재·브라우저 종료·잔류 프로세스 0 | `SELFLOGIC-20261022-BROWSER-FIXTURE-RUN-03` |

Chromium 검증은 별도의 빈 프로필·pairing·데이터와 포트 19491/19492를 사용했습니다. 실제 extension observer와 server action을 통해 `about:blank → initialize → fixture 이동 → input → submit → snapshot → close`를 수행하고 profile 4개 필드와 무관한 요청 필드의 보존도 확인했습니다. receipt API 검증은 호환 함수의 부가 확인이며 live executor의 진행 게이트가 아닙니다. 실행 전후 주요 소스 hash는 동일했습니다. 임시 driver의 긴 Unix socket 경로를 단축했고, 이동 직후 bootstrap URL이 보이는 동안 초기 읽기 전용 snapshot을 최대 10초까지 재조회했습니다. 제품 코드 변경은 없었습니다. 이 검증은 외부 ChatGPT 서비스나 운영 로그인·작업을 사용하는 E2E가 아닙니다.

독립 검증에서 처음 사용한 fake adapter의 부재 판정 시나리오 1개는 실제 adapter의 raw target inventory 계약을 재현하지 못했습니다. 이를 제품 실패로 숨기지 않고 실제 ExtensionManager를 사용하는 부재/물리 존재 시나리오 2개로 교체하여 검증했습니다. 제품 소스 수정은 없었습니다. 최종 검토 판정은 `SCOPE_IN_SCOPE`, `ASSUMPTION_VALID`, `SOLUTION_SCALE_JUSTIFIED_EXPANSION`, `DIFF_TRACEABILITY_COMPLETE`, `GOAL_EVIDENCE_COMPLETE`입니다.

회귀시험의 실제 명령은 `termux-server` 디렉터리에서 다음과 같습니다.

```sh
node --test --test-timeout=10000 --test-concurrency=2 \
  test/length-only-executor.test.mjs test/controller.test.mjs \
  test/drive-watcher.test.mjs test/drive-watcher-current-only.test.mjs \
  test/drive-transport-list.test.mjs test/drive-transport-errors.test.mjs \
  test/cluster-coordinator.test.mjs test/node-claim-admission.test.mjs \
  test/current-control-admission.test.mjs test/server-action-session.test.mjs \
  test/extension-manager.test.mjs test/optimistic-profile-transition.test.mjs \
  test/app-stop-direct-close.test.mjs test/terminal-browser-cleanup.test.mjs \
  test/browser-current-section-probe.test.mjs test/server-package-version.test.mjs
```

늦게 생기는 탭도 한 작업 한 탭 규칙의 대상입니다. 탭 생성 효과 전에 무작위 bootstrap nonce와 `acquisitionPending`을 journal에 저장하고, 실제 ID가 확인되면 그 ID로 교체합니다. 취소/timeout 직후 목록에 없다는 이유만으로 아직 반환되지 않은 생성을 폐기 완료로 간주하지 않습니다. 정확한 late target의 물리 폐기 또는 정확한 소유 브라우저 프로세스 종료를 확인할 때까지 동일 작업의 후속 생성을 보류하며, 재시작 시에도 pending nonce를 보존합니다.

## 자동 적용 계약

현재 앱의 작업·미처리 요청·HTTP 작업·폐기 대기·실제 소유 탭이 모두 없어질 때만 적용합니다. PAUSED와 사용자 개입 작업도 남아 있는 작업입니다. 오래된 `IDLE` 표지만으로 적용하지 않습니다. Drive 조회는 GET 전용이며 파일을 이동하는 일반 dispatch list 경로를 사용하지 않습니다.

적용 직전 정확한 기존 Node 프로세스를 잠시 정지하고 작업 generation·Drive fingerprint·durable state·실제 탭을 다시 확인합니다. 조건이 바뀌면 재개합니다. 비어 있으면 기존 전용 Chromium의 실제 종료를 확인하고 launcher 심볼릭 링크를 원자적으로 교체합니다. 새 프로세스의 소스 commit·launcher·health를 대조한 뒤에만 `APPLIED`로 기록합니다. PID/startTime/bootId/cwd/argv를 대조하고, 실패 시 복구와 45초 재개 watchdog을 둡니다.

예약 manifest는 `~/.selfrun-server/pending/selfrun-server-v4.0.0-dev41-idle-patch.json`, 상태는 같은 경로의 `.status.json`입니다. `WAITING`, `STARTING`, `APPLIED`, `APPLIED_UNVERIFIED`를 구별하며, 예약 등록은 운영 적용 완료를 뜻하지 않습니다. 실제 등록 시각·commit·상태는 RELEASE 인계 결과로 확인합니다.
