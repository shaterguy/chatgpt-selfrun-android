import type { IncomingMessage, ServerResponse } from "node:http";

import { hasValidAndroidReadToken } from "../../src/android-auth.js";
import { getRuntimeConfig } from "../../src/config.js";
import { latestCommand } from "../../src/db.js";
import { NO_STORE_HEADERS, setJson } from "../../src/http.js";

function authorizationHeader(request: IncomingMessage): string | undefined {
  const value = request.headers.authorization;
  return Array.isArray(value) ? value[0] : value;
}

export default async function handler(
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  for (const [name, value] of Object.entries(NO_STORE_HEADERS)) {
    response.setHeader(name, value);
  }
  if ((request.method ?? "GET").toUpperCase() !== "GET") {
    setJson(
      response,
      { status: "error", error: "method_not_allowed" },
      405,
      { Allow: "GET" },
    );
    return;
  }
  if (!getRuntimeConfig().androidReadToken) {
    setJson(
      response,
      {
        status: "error",
        error: "read_auth_not_configured",
      },
      503,
    );
    return;
  }
  if (!hasValidAndroidReadToken(authorizationHeader(request))) {
    setJson(
      response,
      { status: "error", error: "unauthorized" },
      401,
      { "WWW-Authenticate": "Bearer" },
    );
    return;
  }

  try {
    const record = await latestCommand();
    if (!record) {
      setJson(
        response,
        {
          status: "empty",
          command_id: null,
          command: null,
          saved_at: null,
          hash: null,
        },
        200,
      );
      return;
    }
    setJson(
      response,
      {
        status: "ok",
        command_id: record.commandId,
        command: record.command,
        saved_at: record.savedAt,
        hash: record.hash,
      },
      200,
    );
  } catch {
    setJson(
      response,
      {
        status: "error",
        error: "database_unavailable",
      },
      503,
    );
  }
}
