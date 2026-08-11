import { isMcpCapabilityConfigured } from "./capability.js";
import { probeBlob } from "./blob.js";
import { getRuntimeConfig } from "./config.js";
import { NO_STORE_HEADERS, jsonResponse } from "./http.js";

export async function healthResponse(): Promise<Response> {
  const config = getRuntimeConfig();
  let blobAvailable = false;
  try {
    await probeBlob();
    blobAvailable = true;
  } catch {
    blobAvailable = false;
  }
  const androidReadConfigured = Boolean(config.androidReadToken);
  const capabilityConfigured = isMcpCapabilityConfigured();
  const ready = blobAvailable && androidReadConfigured && capabilityConfigured;
  return jsonResponse(
    {
      service: "selfrun-command-bridge",
      status: ready ? "ok" : "error",
      blob: blobAvailable ? "ok" : "unavailable",
      android_read_configured: androidReadConfigured,
      mcp_capability_configured: capabilityConfigured,
    },
    ready ? 200 : 503,
    NO_STORE_HEADERS,
  );
}
