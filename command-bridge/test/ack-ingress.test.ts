import { describe, expect, it } from "vitest";
import { isTerminalAckResumeError } from "../src/ack-ingress.js";
import { eventFingerprint } from "../src/telemetry.js";

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

describe("safe push telemetry fingerprint", () => {
  it("is deterministic and does not expose the event capability", () => {
    const eventId = "EV-0123456789abcdef0123456789abcdef";
    const fingerprint = eventFingerprint(eventId);

    expect(fingerprint).toMatch(/^[0-9a-f]{16}$/);
    expect(eventFingerprint(eventId)).toBe(fingerprint);
    expect(fingerprint).not.toContain(eventId);
  });
});
