import type { IncomingMessage, ServerResponse } from "node:http";

import { setJson } from "../src/http.js";

export default function handler(
  _request: IncomingMessage,
  response: ServerResponse,
): void {
  setJson(
    response,
    {
      service: "selfrun-command-bridge",
      status: "ok",
      endpoints: {
        mcp: "/mcp",
        oauthProtectedResource: "/.well-known/oauth-protected-resource",
        health: "/api/health",
        ready: "/api/ready",
        latest: "/api/selfrun/latest",
      },
    },
    200,
  );
}
