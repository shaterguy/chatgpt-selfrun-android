import { describe, expect, it } from "vitest";
import { parseAck, parseRegistration } from "../src/contracts.js";
import { createCapability } from "../src/security.js";

const registration = {
  schema: "selfrun-watch-register-v1",
  installationId: "INS-0123456789abcdef0123456789abcdef",
  applicationId: "com.shaterguy.chatgptselfrun.drive.test",
  taskId: "SR-20260915-ABCDEF",
  turnId: "SR-20260915-ABCDEF:turn:4",
  resultDocumentId: "1AbcDefGhijkLMNopQRstuVwxyz012345",
  fcmToken: "fcm-token-value-with-enough-length-1234567890"
};

describe("gateway contracts", () => {
  it("accepts exact registration identity but never accepts Drive credentials or bodies", () => {
    expect(parseRegistration(registration)).toMatchObject({ taskId: registration.taskId });
    expect(() => parseRegistration({ ...registration, driveAccessToken: "secret" })).toThrow();
    expect(() => parseRegistration({ ...registration, resultBody: "secret" })).toThrow();
  });

  it("only accepts RECEIVED or PROCESSED ACK state with complete identity", () => {
    const base = {
      schema: "selfrun-push-ack-v1",
      eventId: "EV-0123456789abcdef0123456789abcdef",
      installationId: registration.installationId,
      applicationId: registration.applicationId,
      taskId: registration.taskId,
      turnId: registration.turnId,
      resultDocumentId: registration.resultDocumentId,
      state: "RECEIVED"
    };
    expect(parseAck(base).state).toBe("RECEIVED");
    expect(parseAck({ ...base, state: "PROCESSED" }).state).toBe("PROCESSED");
    expect(() => parseAck({ ...base, state: "SENT" })).toThrow();
    expect(() => parseAck({ ...base, turnId: "" })).toThrow();
  });

  it("creates high entropy URL-safe capabilities", () => {
    const value = createCapability("WK");
    expect(value).toMatch(/^WK-[A-Za-z0-9_-]+$/);
    expect(value.length).toBeGreaterThanOrEqual(46);
    expect(createCapability("WK")).not.toBe(value);
  });
});
