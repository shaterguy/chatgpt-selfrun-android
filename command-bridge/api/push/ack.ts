import type { IncomingMessage, ServerResponse } from "node:http";
import { parseAck } from "../../src/contracts.js";
import { ackHook } from "../../src/hooks.js";
import { errorResponse, json, method, readJson } from "../../src/http.js";

export default async function handler(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    method(request, "POST");
    const ack = parseAck(await readJson(request));
    await ackHook.resume(ack.eventId, ack);
    json(response, 202, { ok: true, state: ack.state });
  } catch (error) {
    errorResponse(response, error);
  }
}
