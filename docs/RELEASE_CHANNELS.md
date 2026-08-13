# SelfRun release channels

이 저장소는 서로 다른 Android application ID를 가진 두 SelfRun 제품 계보를 함께 관리합니다. 정식 릴리스의 기준 브랜치·태그·자산 이름을 섞지 않습니다.

## WebView SelfRun

- WebView canonical branch: `main`
- 개발 브랜치: `selfrun-webview/v<version>-devN`
- 정식 태그: `v<version>`
- application ID: `com.shaterguy.chatgptselfrun`
- 기존 `v0.2.x` 태그와 GitHub Release는 이 계보에 속합니다.

## SelfRun Drive

- Drive canonical branch: `selfrun-drive/main`
- 개발 브랜치: `selfrun-drive/v<version>-devN`
- 정식 태그: `drive-v<version>`
- application ID: `com.shaterguy.chatgptselfrun.drive`
- Drive Release는 저장소의 WebView 중심 Latest 표시를 바꾸지 않도록 `--latest=false`로 생성합니다.

## 불변조건

- Drive 정식 승격을 위해 저장소 기본 브랜치 `main`을 Drive 런타임으로 덮어쓰지 않습니다.
- WebView와 Drive의 application ID, versionCode 계보, 서비스 action, provider authority, 앱 데이터와 세션을 공유하지 않습니다.
- 한 계보의 개발 브랜치를 다른 계보의 정식 브랜치에 병합하지 않습니다.
- 정식 태그는 각 계보의 canonical branch에서 검증된 최종 커밋만 가리킵니다.
- 이미 발행된 정식 태그를 이동하거나 덮어쓰지 않습니다.
