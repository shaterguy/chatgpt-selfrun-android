import type { IncomingMessage, ServerResponse } from "node:http";

import { getRuntimeConfig } from "../src/config.js";
import { setJson } from "../src/http.js";

export default function handler(
  request: IncomingMessage,
  response: ServerResponse,
): void {
  const method = (request.method ?? "GET").toUpperCase();
  if (method === "OPTIONS") {
    response.statusCode = 204;
    response.setHeader("Access-Control-Allow-Origin", "*");
    response.setHeader("Access-Control-Allow-Methods", "GET, OPTIONS");
    response.setHeader("Access-Control-Allow-Headers", "Content-Type");
    response.end();
    return;
  }
  if (method !== "GET") {
    setJson(
      response,
      { error: "method_not_allowed" },
      405,
      { Allow: "GET, OPTIONS" },
    );
    return;
  }
  const config = getRuntimeConfig();
  if (!config.auth0Issuer) {
    setJson(response, { error: "oauth_not_configured" }, 503);
    return;
  }
  setJson(
    response,
    {
      resource: config.mcpResourceUrl,
      authorization_servers: [config.auth0Issuer],
      scopes_supported: ["commands:write"],
    },
    200,
    {
      "Cache-Control": "public, max-age=300",
      "Access-Control-Allow-Origin": "*",
    },
  );
}
