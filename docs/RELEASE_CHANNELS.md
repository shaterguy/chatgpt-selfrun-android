# SelfRun release channels

이 저장소의 기본 개발·정식 계보는 SelfRun Drive입니다. 기존 WebView SelfRun은 과거 릴리스와 후속 유지보수를 위해 별도 브랜치에 보존합니다. 두 제품은 서로 다른 Android application ID와 버전 계보를 유지하며 서로의 코드를 정식 브랜치에 병합하지 않습니다.

## SelfRun Drive

- Drive canonical branch: `main`
- 개발 브랜치: `selfrun-drive/v<version>-devN`
- 릴리스 후보 브랜치: `selfrun-drive/v<version>-rcN`
- 정식 태그: `drive-v<version>`
- application ID: `com.shaterguy.chatgptselfrun.drive`
- 저장소 기본 브랜치와 신규 정식 Release의 기준 제품입니다.

## WebView SelfRun

- WebView maintenance branch: `selfrun-webview/main`
- 개발 브랜치: `selfrun-webview/v<version>-devN`
- 정식 태그: `v<version>`
- application ID: `com.shaterguy.chatgptselfrun`
- 기존 `v0.2.x` 태그와 GitHub Release는 이 계보에 속하며 그대로 보존합니다.

## 불변조건

- 저장소 기본 브랜치 `main`은 SelfRun Drive 정식 런타임만 가리킵니다.
- WebView 소스는 `selfrun-webview/main`에서 독립 보존하며 Drive `main`과 통합·병합하지 않습니다.
- WebView와 Drive의 application ID, versionCode 계보, 서비스 action, provider authority, 앱 데이터와 세션을 공유하지 않습니다.
- 정식 태그는 각 계보의 검증된 최종 커밋만 가리키며 이미 발행된 정식 태그를 이동하거나 덮어쓰지 않습니다.
- Drive 정식 릴리스 전에는 기존 WebView `main` HEAD를 `selfrun-webview/main`에 보존한 뒤 `main`을 검증된 Drive 최종 커밋으로 전환합니다.
