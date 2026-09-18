import type { IncomingMessage, ServerResponse } from "node:http";
import { shouldDeliverDriveContentChange } from "../../src/delivery-policy.js";
import { driveHook } from "../../src/hooks.js";
import { empty, errorResponse, header, method } from "../../src/http.js";

export default async function handler(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    method(request, "POST");
    const channelId = header(request, "x-goog-channel-id", 128);
    const watchKey = header(request, "x-goog-channel-token", 160);
    const resourceState = header(request, "x-goog-resource-state", 64);
    const messageNumber = header(request, "x-goog-message-number", 64);
    const resourceId = header(request, "x-goog-resource-id", 256);
    const rawChanged = request.headers["x-goog-changed"];
    const changed = rawChanged === undefined ? "" : header(request, "x-goog-changed", 256);
    if (!shouldDeliverDriveContentChange(resourceState, changed)) {
      empty(response, 204);
      return;
    }
    await driveHook.resume(watchKey, { channelId, resourceState, changed, messageNumber, resourceId });
    empty(response, 204);
  } catch (error) {
    errorResponse(response, error);
  }
}
