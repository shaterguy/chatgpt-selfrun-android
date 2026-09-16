import type { IncomingMessage, ServerResponse } from "node:http";
import { start } from "workflow/api";
import { parseRegistration } from "../../src/contracts.js";
import { firebaseConfigured } from "../../src/firebase.js";
import { errorResponse, json, method, readJson } from "../../src/http.js";
import { createCapability } from "../../src/security.js";

const WATCH_LIFETIME_MS = 23 * 60 * 60 * 1000;
const WATCH_WORKFLOW = { workflowId: "workflow//workflows/watch.ts//watchWorkflow" } as const;

export default async function handler(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    method(request, "POST");
    if (!firebaseConfigured()) {
      json(response, 503, { ok: false, error: "firebase_not_configured" });
      return;
    }
    const registration = parseRegistration(await readJson(request));
    const watchKey = createCapability("WK");
    const channelId = createCapability("CH");
    const expirationMs = Date.now() + WATCH_LIFETIME_MS;
    const hostHeader = request.headers["x-forwarded-host"] ?? request.headers.host;
    const host = Array.isArray(hostHeader) ? hostHeader[0] : hostHeader;
    if (!host || !/^[A-Za-z0-9.-]+(?::[0-9]+)?$/.test(host)) throw new Error("valid host required");
    const webhookUrl = `https://${host}/api/drive/webhook`;

    const run = await start(WATCH_WORKFLOW, [{ ...registration, watchKey, channelId, expirationMs }]);
    json(response, 202, {
      ok: true,
      watchKey,
      channelId,
      webhookUrl,
      expirationMs,
      workflowRunId: run.runId,
    });
  } catch (error) {
    errorResponse(response, error);
  }
}
