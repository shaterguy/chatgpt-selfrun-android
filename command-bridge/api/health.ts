import type { IncomingMessage, ServerResponse } from "node:http";

import { getRuntimeConfig } from "../src/config.js";
import { probeDatabase } from "../src/db.js";
import { setJson } from "../src/http.js";

export default async function handler(
  _request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  try {
    const config = getRuntimeConfig();
    await probeDatabase();
    setJson(
      response,
      {
        service: "selfrun-command-bridge",
        status: "ok",
        database: "ok",
        auth_configured: Boolean(
          config.auth0Issuer &&
            config.auth0Audience &&
            config.auth0AllowedSubjects.length,
        ),
        android_read_configured: Boolean(config.androidReadToken),
      },
      200,
    );
  } catch {
    setJson(
      response,
      {
        service: "selfrun-command-bridge",
        status: "error",
        database: "unavailable",
      },
      503,
    );
  }
}
