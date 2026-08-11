# SelfRun Command Bridge

개인용 SelfRun 명령 저장·조회 브리지입니다. 최신 명령 하나만 Private Blob에 보관하며, 명령 이력·계정·범용 command bus를 만들지 않습니다.

## Production endpoints

- GET /api/health
- GET /api/selfrun/latest
- POST /mcp/<capability>

정확한 고엔트로피 capability path만 MCP endpoint로 동작합니다. `/mcp`, 잘못된 capability와 `/api/mcp`는 도구나 인증 구조를 알리지 않는 404입니다. Android 최신 명령 조회는 `SELF_RUN_ANDROID_READ_TOKEN` Bearer token으로 별도 보호됩니다.

## Required environment variables

### Vercel

- SELF_RUN_ANDROID_READ_TOKEN
- SELF_RUN_MCP_CAPABILITY: 32바이트 base64url 난수
- Vercel Blob store의 project-scoped OIDC 설정 (`VERCEL_OIDC_TOKEN`, `BLOB_STORE_ID`)

실제 값은 저장소나 로그에 기록하지 않습니다.

## Local commands

~~~text
npm ci
npm run typecheck
npm test
~~~

Vercel Blob의 `selfrun/latest-command.json`만 사용합니다. 저장은 private same-path overwrite이며 읽기는 `useCache: false`로 origin에서 최신 값을 확인합니다.

## Android development build

- versionName: 0.1.1-dev1
- versionCode: 9
- command bridge URL: https://selfrun-command-bridge.vercel.app
