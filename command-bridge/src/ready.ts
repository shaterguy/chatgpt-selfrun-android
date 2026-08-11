import { hasValidAndroidReadToken } from "./android-auth.js";
import { probeBlob } from "./blob.js";
import { getRuntimeConfig } from "./config.js";
import { NO_STORE_HEADERS, jsonResponse } from "./http.js";

export async function readyResponse(
  authorizationHeader: string | undefined,
): Promise<Response> {
  if (!hasValidAndroidReadToken(authorizationHeader)) {
    return jsonResponse(
      { service: "selfrun-command-bridge", status: "error", error: "unauthorized" },
      401,
      { ...NO_STORE_HEADERS, "WWW-Authenticate": "Bearer" },
    );
  }
  if (!getRuntimeConfig().androidReadToken) {
    return jsonResponse(
      { service: "selfrun-command-bridge", status: "error", error: "read_auth_not_configured" },
      503,
      NO_STORE_HEADERS,
    );
  }

  try {
    const blobStatus = await probeBlob();
    return jsonResponse(
      {
        service: "selfrun-command-bridge",
        status: blobStatus === "empty" ? "empty-ready" : "ok",
        blob: blobStatus,
      },
      200,
      NO_STORE_HEADERS,
    );
  } catch {
    return jsonResponse(
      {
        service: "selfrun-command-bridge",
        status: "error",
        blob: "unavailable",
        error: "blob_unavailable",
      },
      503,
      NO_STORE_HEADERS,
    );
  }
}
