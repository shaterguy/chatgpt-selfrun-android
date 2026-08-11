import { hasValidAndroidReadToken } from "./android-auth.js";
import { latestCommand } from "./blob.js";
import { getRuntimeConfig } from "./config.js";
import { NO_STORE_HEADERS, jsonResponse } from "./http.js";

export async function latestCommandResponse(
  authorizationHeader: string | undefined,
): Promise<Response> {
  if (!getRuntimeConfig().androidReadToken) {
    return jsonResponse(
      { status: "error", error: "read_auth_not_configured" },
      503,
      NO_STORE_HEADERS,
    );
  }
  if (!hasValidAndroidReadToken(authorizationHeader)) {
    return jsonResponse(
      { status: "error", error: "unauthorized" },
      401,
      { ...NO_STORE_HEADERS, "WWW-Authenticate": "Bearer" },
    );
  }

  try {
    const record = await latestCommand();
    if (!record) {
      return jsonResponse(
        { status: "empty", command: null, saved_at: null, hash: null },
        200,
        NO_STORE_HEADERS,
      );
    }
    return jsonResponse(
      {
        status: "ok",
        command: record.command,
        saved_at: record.savedAt,
        hash: record.hash,
      },
      200,
      NO_STORE_HEADERS,
    );
  } catch {
    return jsonResponse(
      { status: "error", error: "blob_unavailable" },
      503,
      NO_STORE_HEADERS,
    );
  }
}
