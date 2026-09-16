import { readFileSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { describe, expect, it } from "vitest";
import { isTerminalAckResumeError } from "../src/ack-ingress.js";
import { buildPushTelemetry, eventFingerprint } from "../src/telemetry.js";

const here = dirname(fileURLToPath(import.meta.url));

describe("ACK ingress terminal-stale classification", () => {
  it("treats ended or missing workflow hooks as terminal", () => {
    const hookGone = Object.assign(new Error("hook not found"), { name: "HookNotFoundError" });
    const runExpired = Object.assign(new Error("run expired"), { name: "RunExpiredError" });

    expect(isTerminalAckResumeError(hookGone)).toBe(true);
    expect(isTerminalAckResumeError(runExpired)).toBe(true);
  });

  it("treats unavailable historical deployment keys as terminal", () => {
    expect(isTerminalAckResumeError(new Error('Invalid response from Vercel API, missing "key" field'))).toBe(true);
    expect(isTerminalAckResumeError(new Error("Failed to fetch run key for wrun_old (deployment dpl_old): HTTP 404"))).toBe(true);
    expect(isTerminalAckResumeError(new Error("Failed to fetch run key for wrun_old (deployment dpl_old): HTTP 410"))).toBe(true);
  });

  it("keeps transient workflow failures retryable", () => {
    expect(isTerminalAckResumeError(new Error("Failed to fetch run key for wrun_live (deployment dpl_live): HTTP 500"))).toBe(false);
    expect(isTerminalAckResumeError(new Error("temporary workflow backend failure"))).toBe(false);
  });
});

describe("safe push telemetry", () => {
  it("fingerprints the event capability without exposing it", () => {
    const eventId = "EV-0123456789abcdef0123456789abcdef";
    const fingerprint = eventFingerprint(eventId);

    expect(fingerprint).toMatch(/^[0-9a-f]{16}$/);
    expect(eventFingerprint(eventId)).toBe(fingerprint);
    expect(fingerprint).not.toContain(eventId);
  });

  it("builds correlatable state telemetry without raw routing capabilities", () => {
    const eventId = "EV-0123456789abcdef0123456789abcdef";
    const telemetry = buildPushTelemetry(eventId, "ACK_RESUMED", "RECEIVED");

    expect(telemetry).toEqual({
      event: eventFingerprint(eventId),
      stage: "ACK_RESUMED",
      state: "RECEIVED",
    });
    expect(JSON.stringify(telemetry)).not.toContain(eventId);
  });
});

describe("production wiring", () => {
  it("drains only terminal-stale ACKs while leaving transient failures on the error path", () => {
    const source = readFileSync(resolve(here, "../api/push/ack.ts"), "utf8");
    expect(source).toContain("isTerminalAckResumeError(error)");
    expect(source).toContain("stale: true");
    expect(source).toContain("errorResponse(response, error)");
    expect(source).toContain('buildPushTelemetry(ack.eventId, "ACK_RESUMED", ack.state)');
    expect(source).toContain('buildPushTelemetry(ack.eventId, "ACK_TERMINAL_STALE", ack.state)');
  });

  it("logs successful FCM sends with the same safe event fingerprint", () => {
    const source = readFileSync(resolve(here, "../src/firebase.ts"), "utf8");
    expect(source).toContain('buildPushTelemetry(push.eventId, "FCM_SENT")');
  });

  it("exposes a distinct server candidate version for objective promotion checks", () => {
    const source = readFileSync(resolve(here, "../api/health.ts"), "utf8");
    expect(source).toContain('version: "3.2.5-dev4-wdk3"');
  });
});
