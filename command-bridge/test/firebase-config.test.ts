import { generateKeyPairSync } from "node:crypto";
import { afterEach, describe, expect, it } from "vitest";
import {
  firebaseConfigurationStatus,
  normalizeFirebasePrivateKey,
} from "../src/firebase.js";

const privateKeyPem = generateKeyPairSync("rsa", { modulusLength: 2048 })
  .privateKey.export({ type: "pkcs8", format: "pem" })
  .toString();

const originalEnvironment = {
  projectId: process.env.FIREBASE_PROJECT_ID,
  clientEmail: process.env.FIREBASE_CLIENT_EMAIL,
  privateKey: process.env.FIREBASE_PRIVATE_KEY,
};

function restore(name: string, value: string | undefined): void {
  if (value === undefined) delete process.env[name];
  else process.env[name] = value;
}

function configure(rawPrivateKey: string): void {
  process.env.FIREBASE_PROJECT_ID = "selfrun-test-project";
  process.env.FIREBASE_CLIENT_EMAIL = "selfrun-test@example.invalid";
  process.env.FIREBASE_PRIVATE_KEY = rawPrivateKey;
}

afterEach(() => {
  restore("FIREBASE_PROJECT_ID", originalEnvironment.projectId);
  restore("FIREBASE_CLIENT_EMAIL", originalEnvironment.clientEmail);
  restore("FIREBASE_PRIVATE_KEY", originalEnvironment.privateKey);
});

describe("Firebase credential normalization", () => {
  it.each([
    ["pem", privateKeyPem],
    ["escaped-newlines", privateKeyPem.replace(/\n/g, "\\n")],
    ["json-string", JSON.stringify(privateKeyPem)],
    ["base64-pem", Buffer.from(privateKeyPem, "utf8").toString("base64")],
    ["service-account-json", JSON.stringify({ private_key: privateKeyPem })],
    [
      "base64-service-account-json",
      Buffer.from(JSON.stringify({ private_key: privateKeyPem }), "utf8").toString("base64"),
    ],
  ])("accepts %s private-key encoding", (_label, rawPrivateKey) => {
    configure(rawPrivateKey);
    expect(normalizeFirebasePrivateKey(rawPrivateKey)).toBe(privateKeyPem.trim());
    expect(firebaseConfigurationStatus()).toEqual({ configured: true, reason: "ok" });
  });

  it("rejects a malformed private key without exposing its content", () => {
    configure("not-a-private-key");
    expect(firebaseConfigurationStatus()).toEqual({
      configured: false,
      reason: "invalid_private_key",
    });
  });

  it("reports missing environment separately", () => {
    delete process.env.FIREBASE_PROJECT_ID;
    delete process.env.FIREBASE_CLIENT_EMAIL;
    delete process.env.FIREBASE_PRIVATE_KEY;
    expect(firebaseConfigurationStatus()).toEqual({ configured: false, reason: "missing_env" });
  });
});
