import { NO_STORE_HEADERS, jsonResponse } from "./http.js";

export function healthResponse(): Response {
  return jsonResponse(
    {
      service: "selfrun-command-bridge",
      status: "ok",
    },
    200,
    NO_STORE_HEADERS,
  );
}
