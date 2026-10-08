# 기기 적용 준비안 — fixture 1.0.1

## 고정 신원 및 검증 상태

- 후보 앱: 4.0.10-dev3 / versionCode 4002016. 후보 서버: 4.0.0-dev5.
- 후보 소스: e8a4e06f66025f9a7808dc1cc452c7470e06950e; root tree e906d245498fc07c587b271413acb4469cf41822. 서명 산출물 기준 CI 37793257908, attempt 1.
- 본 fixture는 별도 프로그램 1.0.1 / versionCode 2입니다. 앱 및 서버 소스는 바꾸거나 다시 빌드하지 않습니다.
- CI가 completed/success이며 SOURCE_COMMIT, APK SHA manifest, 고정 인증서가 모두 일치해야 합니다. always() 업로드 파일이 있다는 사실만으로 통과 처리하지 않습니다.
- 정식 업데이트 기준: 휴대폰 4.0.8(code4002010), 태블릿 4.0.0(code4001000). 정식 APK SHA는 실행 script에 고정했습니다.
- TEST 기준: 휴대폰 code4002010의 CI run37489163965/artifact11423749914, 태블릿 code4000002의 run36166420867/artifact10877133826. 두 run은 success이나 실제 설치된 TEST APK SHA와 각 artifact 파일의 동일성은 미확인입니다. 임의 재빌드 APK를 기준으로 쓰지 않습니다.

## 별도 격리 CI 실행

`fixture-ci-workflow.yml`은 검토 후 .github/workflows/selfrun-upgrade-fixture-v1.0.1.yml에 추가하고, 나머지 소스는 tools/upgrade-fixture-v1.0.1에 둡니다. 새 불변 branch selfrun-upgrade-fixture/v1.0.1-e8a4e06의 push에만 반응합니다. contents/read와 actions/read만 사용합니다.

네 matrix job은 각각 새로운 에뮬레이터를 사용합니다. 기존 정식/TEST 인증서와 기존 서명 script·기존 CI secret을 그대로 사용하여 fixture만 서명합니다. 키 새 생성/교체/반출이 없습니다. PHONE_TEST_INSTALLED_APK_SHA256 및 TABLET_TEST_INSTALLED_APK_SHA256 값은 읽기 전용 진단에서 확인한 실제 설치 TEST APK SHA여야 합니다. 미설정이면 TEST job은 명시적으로 차단됩니다. 새 앱 권한 또는 기기 보안 설정을 추가하지 않습니다. 변수 입력·CI 게시 자체는 부모 검토 후 기존 MANUAL 흐름에서 처리합니다.

seed → adb install -r → verify에서 paused 합성 작업/이벤트, 계정-folder 바인딩, 실행 설정, 미소비 입력, UID와 전체 선택 snapshot SHA가 일치해야 합니다. 계정 바인딩 저장 보존은 실제 Google/ChatGPT 로그인 토큰이나 권한 유효성을 증명하지 않습니다. fixture APK는 에뮬레이터에만 설치하며 사용자 기기에는 배포하지 않습니다.

## 실제 기기 적용 순서 — 아직 실행하지 않음

1. 부모가 exact source CI 성공과 정식/TEST 각각의 데이터보존 fixture 증거를 확인합니다. 현재 TEST 기준 신원이 미확인이므로 전체 통과는 주장할 수 없습니다.
2. 각 기기의 최신 읽기 전용 preflight로 실제 SERVER_NAME, 실행 루트/호출 script, 앱 package/versionCode/UID/공개 APK SHA/인증서, 활성 작업과 서버 lifecycle/cleanup/pending을 확인합니다. 과거 DONE/IDLE만 재사용하지 않습니다. 휴대폰 물리 모델 Galaxy S26 Ultra의 서버 노드명은 Galaxy-S25입니다. 전체 environ, 인증 파일, 쿠키·토큰, 사용자 대화 본문을 수집하지 않습니다.
3. 기존 앱의 작업 목록·완료/보류 상태, 계정 연결 표시, folder 연결 표시, 작업 방식·타이머·프로필 설정을 기기 화면의 기존 기능으로 설치 전후 비교합니다. 앱 sandbox DB는 Termux에서 읽을 수 없으므로 무권한 cloud 진단으로 실제 데이터 보존이 증명됐다고 말하지 않습니다. 새로운 ADB/root/접근성/알 수 없는 앱 설치 허용을 켜지 않습니다.
4. 실행 중 작업이 없고 queued/running/dispatching, 열린 generation, pending cleanup이 모두 없다는 조건에서 해당 기기의 기존 앱을 동일 package·동일 인증서 APK로 업데이트합니다. formal과 TEST는 각자의 package 및 인증서를 유지하며 서로 데이터를 옮기지 않습니다. 삭제/데이터 지우기/초기화/로그아웃/계정 재연결을 수행하지 않습니다.
5. 가능한 최소 사용자 조작은 준비된 정확한 APK를 기존 허용된 다운로드/파일 열기 앱에서 열고 Android 설치 화면의 '업데이트'를 누르는 것입니다. 정식과 TEST 둘 다 갱신하면 기기별 두 번입니다. 이후 기존 SelfRun 화면에서 작업·설정·계정 연결 표시를 확인합니다. Termux 명령 입력이나 실행마다 확인을 요구하지 않습니다. 현재 Termux에는 silent INSTALL_PACKAGES가 없으므로 무인 설치 성공을 가정하지 않습니다.
6. 기존 허용된 설치 경로가 없거나 installer가 '이 출처 허용', 추가 권한, 계정 연결을 요구하면 변경하지 않고 필요한 출처/권한 또는 연결 항목만 부모에게 보고합니다. 그 보안 설정 변경은 별도 승인 경계입니다. 설치 실패·서명 불일치·설정 누락이면 앱 삭제로 우회하지 않습니다.
7. 업데이트 후 read-only package identity(4002016/4.0.10-dev3, 같은 UID, exact APK SHA/cert)와 실제 작업/계정/설정 유지가 확인될 때만 서버 전환 준비를 진행합니다. 앱 재시작 후 작업이 자동 재개됐으면 다시 idle gate를 확인합니다. 임의 프롬프트 전송 테스트는 하지 않습니다.
8. 휴대폰 서버는 새 불변 dev5 디렉터리에 정확한 commit과 source manifest를 두고 기존 상태를 안전하게 보존하는 검토된 서비스 단위 전환만 합니다. caller/symlink/실행 경로의 이전값을 기록하고 기존 배포를 덮어쓰지 않습니다. 실패 시 서비스 caller를 이전 서버 배포로 복구합니다. APK downgrade/uninstall로 롤백하지 않습니다. 새 앱의 데이터는 그대로 보존합니다.
9. 태블릿 브라우저와 초기화 모듈을 updater가 재사용하므로 이번 준비 작업에서는 태블릿 프로세스·서버·브라우저를 변경하거나 재시작하지 않습니다. 별도 적용 승인과 updater 작업 없음 확인이 있어야 이후 전환을 검토할 수 있습니다.

## 남은 게이트

서명 후보 exact CI 성공/산출물 검증, 실제 TEST APK SHA, 네 격리 업데이트 실행, 실제 기기 설치 후 데이터·연결 상태 확인 및 최신 idle preflight가 남았습니다. 본 문서는 기기 설치·서비스 전환 명령이 아닙니다.
