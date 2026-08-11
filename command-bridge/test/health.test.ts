import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({ probeBlob: vi.fn() }));

vi.mock("../src/blob.js", () => testMocks);

import { healthResponse } from "../src/health.js";

describe("SelfRun health contract", () => {
  beforeEach(() => {
    process.env.SELF_RUN_ANDROID_READ_TOKEN = "read-token";
    process.env.SELF_RUN_MCP_CAPABILITY = "a".repeat(43);
    testMocks.probeBlob.mockReset();
  });

  it("reports only readiness booleans and Blob availability", async () => {
    testMocks.probeBlob.mockResolvedValue(undefined);
    const response = await healthResponse();
    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "ok",
      blob: "ok",
      android_read_configured: true,
      mcp_capability_configured: true,
    });
  });

  it("does not expose secrets when Blob is unavailable", async () => {
    testMocks.probeBlob.mockRejectedValue(new Error("unavailable"));
    const response = await healthResponse();
    expect(response.status).toBe(503);
    const body = await response.text();
    expect(body).toContain('"blob":"unavailable"');
    expect(body).not.toContain("read-token");
    expect(body).not.toContain(process.env.SELF_RUN_MCP_CAPABILITY);
  });
});
