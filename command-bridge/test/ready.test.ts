import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({ probeBlob: vi.fn() }));

vi.mock("../src/blob.js", () => testMocks);

import { readyResponse } from "../src/ready.js";

describe("SelfRun readiness contract", () => {
  beforeEach(() => {
    process.env.SELF_RUN_ANDROID_READ_TOKEN = "read-token";
    testMocks.probeBlob.mockReset();
  });

  afterEach(() => {
    delete process.env.SELF_RUN_ANDROID_READ_TOKEN;
  });

  it("requires the Android bearer token before probing Blob", async () => {
    const response = await readyResponse(undefined);
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBe("Bearer");
    expect(testMocks.probeBlob).not.toHaveBeenCalled();
  });

  it("returns the same unauthorized response for an invalid configured bearer", async () => {
    const response = await readyResponse("Bearer invalid");
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBe("Bearer");
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "error",
      error: "unauthorized",
    });
    expect(testMocks.probeBlob).not.toHaveBeenCalled();
  });

  it("does not reveal missing token configuration to an anonymous caller", async () => {
    delete process.env.SELF_RUN_ANDROID_READ_TOKEN;
    const response = await readyResponse(undefined);
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBe("Bearer");
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "error",
      error: "unauthorized",
    });
    expect(testMocks.probeBlob).not.toHaveBeenCalled();
  });

  it("does not reveal missing token configuration to an invalid bearer caller", async () => {
    delete process.env.SELF_RUN_ANDROID_READ_TOKEN;
    const response = await readyResponse("Bearer invalid");
    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toBe("Bearer");
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "error",
      error: "unauthorized",
    });
    expect(testMocks.probeBlob).not.toHaveBeenCalled();
  });

  it("returns empty-ready when the latest object has not been created", async () => {
    testMocks.probeBlob.mockResolvedValue("empty");
    const response = await readyResponse("Bearer read-token");
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "empty-ready",
      blob: "empty",
    });
  });

  it("returns ready without reading the command body", async () => {
    testMocks.probeBlob.mockResolvedValue("ok");
    const response = await readyResponse("Bearer read-token");
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "ok",
      blob: "ok",
    });
  });

  it("returns 503 for Blob, OIDC, or store failures", async () => {
    testMocks.probeBlob.mockRejectedValue(new Error("unavailable"));
    const response = await readyResponse("Bearer read-token");
    expect(response.status).toBe(503);
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "error",
      blob: "unavailable",
      error: "blob_unavailable",
    });
  });
});
