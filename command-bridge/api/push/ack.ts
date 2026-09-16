import type { IncomingMessage, ServerResponse } from "node:http";
import { isTerminalAckResumeError } from "../../src/ack-ingress.js";
import { parseAck } from "../../src/contracts.js";
import { ackHook } from "../../src/hooks.js";
import { errorResponse, json, method, readJson } from "../../src/http.js";
import { buildPushTelemetry } from "../../src/telemetry.js";

export default async function handler(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    method(request, "POST");
    const ack = parseAck(await readJson(request));
    try {
      await ackHook.resume(ack.eventId, ack);
      console.info("SELF_RUN_PUSH", JSON.stringify(buildPushTelemetry(ack.eventId, "ACK_RESUMED", ack.state)));
      json(response, 202, { ok: true, state: ack.state });
      return;
    } catch (error) {
      if (isTerminalAckResumeError(error)) {
        console.info("SELF_RUN_PUSH", JSON.stringify(buildPushTelemetry(ack.eventId, "ACK_TERMINAL_STALE", ack.state)));
        json(response, 202, { ok: true, state: ack.state, stale: true });
        return;
      }
      throw error;
    }
  } catch (error) {
    errorResponse(response, error);
  }
}
