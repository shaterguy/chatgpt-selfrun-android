import { createHash } from "node:crypto";
import type { AckState } from "./contracts.js";

export type PushTelemetryStage = "FCM_SENT" | "ACK_RESUMED" | "ACK_TERMINAL_STALE";

export interface PushTelemetry {
  event: string;
  stage: PushTelemetryStage;
  state?: AckState;
}

export function eventFingerprint(eventId: string): string {
  return createHash("sha256").update(eventId, "utf8").digest("hex").slice(0, 16);
}

export function buildPushTelemetry(
  eventId: string,
  stage: PushTelemetryStage,
  state?: AckState,
): PushTelemetry {
  return {
    event: eventFingerprint(eventId),
    stage,
    ...(state ? { state } : {}),
  };
}
