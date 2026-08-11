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
        health: "/api/health",
        latest: "/api/selfrun/latest",
      },
    },
    200,
  );
}
