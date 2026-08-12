# ChatGPT SelfRun Android

ChatGPT에서 하나의 conversation을 처음부터 끝까지 유지하면서 설계, 구현, 독립 검수, 재작업과 완료 판정을 여러 턴에 걸쳐 이어 가는 Android 실행기입니다.

이 프로젝트는 `chatgpt-prompt-scheduler-android`의 Automation Run과 독립적입니다. Chat↔Work 두 대화방 중계를 사용하지 않습니다.

## 실행 모드

### Work

- 프로젝트의 Work 대화 하나를 끝까지 유지합니다.
- 첫 턴은 Sol xHigh로 설계·계획을 수행합니다.
- 이후 assistant가 다음 역할과 모델/추론을 신호로 선택합니다.
- 모델/추론은 역할에 고정하지 않고 다음 작업의 난도와 불확실성에 따라 동적으로 결정합니다.
- 가장 낮은 허용 조합은 Luna Max입니다.
- 앱은 기존 Prompt Scheduler의 DOM 방식처럼 모델/추론 옵션을 단계적으로 열고 실제 current/selected 상태를 readback한 뒤 다음 턴을 보냅니다.

### 일반 Chat

- 프로젝트의 일반 Chat 대화 하나를 끝까지 유지합니다.
- 앱은 모델과 추론 설정을 변경하지 않습니다.
- Planner, Builder, Verifier, Decision 등 역할만 턴마다 바꿉니다.

## Bootstrap

앱은 사용자 명령 앞에 실행 모드를 자동 삽입합니다.

```text
[SELF_RUN_BOOTSTRAP 0.1.0 <RUN_ID> MODE=WORK]
```

또는

```text
[SELF_RUN_BOOTSTRAP 0.1.0 <RUN_ID> MODE=CHAT]
```

다음 턴은 상세 작업 지시 대신 같은 conversation에 아래 최소 프롬프트만 보냅니다.

```text
[SELF_RUN_CONTINUE <RUN_ID>]
```

## WebView

- Android 화면: 기기 기본 방향 및 시스템 라이트·다크 모드
- 자동화 가상 디스플레이: 1440×900 / 160 dpi
- 실제 Android WebView user agent
- wide viewport / overview mode
- 로그인 화면과 백그라운드 자동화는 같은 앱 CookieManager를 사용합니다.

## 현재 최신 릴리스

- 릴리스 태그: `v0.2.0`
- versionName: `0.2.0`
- versionCode: `9`
- applicationId: `com.shaterguy.chatgptselfrun`
- Android 8.0 이상

GitHub Actions가 SelfRun 정책 검사, 단위 테스트, release APK 빌드, zipalign, 패키지·버전 검증과 SHA-256 계산을 수행합니다.
