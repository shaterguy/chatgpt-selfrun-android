import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import {
  ackMatchesIdentity,
  buildPushEnvelope,
  shouldDeliverDriveContentChange,
  shouldTerminateDelivery,
} from "../src/delivery-policy.js";

const identity = {
  installationId: "INS-0123456789abcdef0123456789abcdef",
  applicationId: "com.shaterguy.chatgptselfrun.drive.test",
  taskId: "SR-20260915-ABCDEF",
  turnId: "SR-20260915-ABCDEF:turn:4",
  resultDocumentId: "1AbcDefGhijkLMNopQRstuVwxyz012345",
};

describe("delivery policy", () => {
  it("treats only PROCESSED as terminal", () => {
    expect(shouldTerminateDelivery("RECEIVED")).toBe(false);
    expect(shouldTerminateDelivery("PROCESSED")).toBe(true);
  });

  it("accepts ACK only for the exact event and routing identity", () => {
    const ack = { schema: "selfrun-push-ack-v1" as const, eventId: "EV-0123456789abcdef0123456789abcdef", ...identity, state: "PROCESSED" as const };
    expect(ackMatchesIdentity(ack, ack.eventId, identity)).toBe(true);
    expect(ackMatchesIdentity({ ...ack, turnId: "other" }, ack.eventId, identity)).toBe(false);
    expect(ackMatchesIdentity(ack, "different-event", identity)).toBe(false);
  });

  it("delivers only actual watched-file content updates, never message sequence or metadata changes alone", () => {
    expect(shouldDeliverDriveContentChange("sync", "")).toBe(false);
    expect(shouldDeliverDriveContentChange("update", "")).toBe(false);
    expect(shouldDeliverDriveContentChange("update", "properties")).toBe(false);
    expect(shouldDeliverDriveContentChange("update", "permissions, parents")).toBe(false);
    expect(shouldDeliverDriveContentChange("update", "content")).toBe(true);
    expect(shouldDeliverDriveContentChange("update", "content, properties")).toBe(true);
    expect(shouldDeliverDriveContentChange("change", "content")).toBe(false);
  });

  it("builds a minimal push envelope without transport or Drive secrets", () => {
    const push = buildPushEnvelope("EV-0123456789abcdef0123456789abcdef", identity);
    expect(push).toEqual({ schema: "selfrun-push-v1", type: "RESULT_CHANGED", eventId: "EV-0123456789abcdef0123456789abcdef", ...identity });
    expect(push).not.toHaveProperty("fcmToken");
    expect(push).not.toHaveProperty("watchKey");
    expect(push).not.toHaveProperty("driveAccessToken");
  });

  it("keeps watching while newer Drive changes preempt an unresolved delivery", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const source = readFileSync(resolve(here, "../workflows/watch.ts"), "utf8");
    expect(source).toContain("const driveIterator = driveEvents[Symbol.asyncIterator]();");
    expect(source).toContain("shouldDeliverDriveContentChange(signal.resourceState, signal.changed)");
    expect(source).toContain('kind: "drive" as const');
    expect(source).toContain("pendingDrive.then");
    expect(source).toContain("active.pendingAck.then");
    expect(source).toContain("active.retry.onMatchingAck(ack.state);");
    expect(source).toContain("active = null;");
    expect(source).not.toContain('return { status: "PROCESSED", eventId };');
    expect(source).not.toContain("for (let attempt = 0; attempt < 150; attempt += 1)");
  });

  it("filters non-content Drive notifications at webhook ingress before resuming the durable workflow", () => {
    const here = dirname(fileURLToPath(import.meta.url));
    const source = readFileSync(resolve(here, "../api/drive/webhook.ts"), "utf8");
    expect(source).toContain('request.headers["x-goog-changed"]');
    expect(source).toContain("shouldDeliverDriveContentChange(resourceState, changed)");
    expect(source).toContain("changed, messageNumber, resourceId");
  });
});
