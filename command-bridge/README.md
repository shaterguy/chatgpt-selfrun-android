# SelfRun Command Bridge

개인용 SelfRun 명령 저장·조회 브리지입니다. 최신 명령 하나만 Private Blob에 보관하며, 명령 이력·계정·범용 command bus를 만들지 않습니다.

## Production endpoints

- GET /api/health
- GET /api/ready
- GET /api/selfrun/latest
- POST /mcp
- GET /.well-known/oauth-protected-resource

/mcp는 Auth0 OAuth 2.1과 commands:write scope로 보호됩니다. 공개 health는 상수비용 liveness만 확인하고, /api/ready는 Android Bearer token으로 보호된 Blob metadata readiness를 제공합니다. Android 최신 명령 조회는 SELF_RUN_ANDROID_READ_TOKEN Bearer token으로 별도 보호됩니다.

## Required environment variables

### Vercel

- SELF_RUN_ANDROID_READ_TOKEN
- AUTH0_ISSUER
- AUTH0_AUDIENCE
- AUTH0_ALLOWED_SUB
- 선택: AUTH0_JWKS_URL, MCP_RESOURCE_URL
- Vercel Blob store의 project-scoped OIDC 설정 (VERCEL_OIDC_TOKEN, BLOB_STORE_ID)

실제 값은 저장소나 로그에 기록하지 않습니다.

## Local commands

    npm ci
    npm run typecheck
    npm test

Vercel Blob의 selfrun/latest-command.json만 사용합니다. 저장은 private same-path overwrite이며 읽기는 useCache: false로 origin에서 최신 값을 확인합니다. readiness는 명령 본문을 읽지 않고 metadata head만 호출합니다.

## Android development build

- versionName: 0.1.1-dev1
- versionCode: 9
- command bridge URL: https://selfrun-command-bridge.vercel.app
