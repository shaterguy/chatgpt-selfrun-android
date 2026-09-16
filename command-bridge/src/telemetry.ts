import { createHash } from "node:crypto";

export function eventFingerprint(eventId: string): string {
  return createHash("sha256").update(eventId, "utf8").digest("hex").slice(0, 16);
}
