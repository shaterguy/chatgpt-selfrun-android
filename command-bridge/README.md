# SelfRun Command Bridge

개인용 SelfRun 명령 저장·조회 브리지입니다.

## Production endpoints

- GET /api/health
- GET /api/selfrun/latest
- POST /mcp
- GET /.well-known/oauth-protected-resource

save_selfrun_command는 MCP OAuth scope commands:write가 필요합니다. Android 최신 명령 조회는 SELF_RUN_ANDROID_READ_TOKEN Bearer token으로 별도 보호됩니다.

## Required environment variables

### Vercel

- DATABASE_URL
- SELF_RUN_ANDROID_READ_TOKEN
- AUTH0_ISSUER
- AUTH0_AUDIENCE
- AUTH0_ALLOWED_SUB
- 선택: AUTH0_JWKS_URL, MCP_RESOURCE_URL

실제 값은 저장소나 로그에 기록하지 않습니다.

## Local commands

~~~text
npm ci
npm run typecheck
npm test
~~~

schema.sql은 Neon production branch에 한 번 적용해야 합니다.

## Android development build

- versionName: 0.1.0-dev6
- versionCode: 8
- command bridge URL: https://selfrun-command-bridge.vercel.app
