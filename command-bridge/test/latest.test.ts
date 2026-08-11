import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({ latestCommand: vi.fn() }));

vi.mock("../src/blob.js", () => testMocks);

import { commandHash } from "../src/command.js";
import { latestCommandResponse } from "../src/latest.js";

describe("SelfRun latest command API contract", () => {
  beforeEach(() => {
    process.env.SELF_RUN_ANDROID_READ_TOKEN = "read-token";
    testMocks.latestCommand.mockReset();
  });

  it("returns the command with no-store headers for the correct token", async () => {
    const command = "한글\n```json\n{\\\"x\\\":1}\n```";
    testMocks.latestCommand.mockResolvedValue({
      command,
      savedAt: "2026-08-12T00:00:00.000Z",
      hash: commandHash(command),
    });
    const response = await latestCommandResponse("Bearer read-token");
    expect(response.status).toBe(200);
    expect(response.headers.get("cache-control")).toContain("no-store");
    await expect(response.json()).resolves.toEqual({
      status: "ok",
      command,
      saved_at: "2026-08-12T00:00:00.000Z",
      hash: commandHash(command),
    });
  });

  it("blocks missing and incorrect read tokens", async () => {
    await expect((await latestCommandResponse(undefined)).status).toBe(401);
    await expect((await latestCommandResponse("Bearer wrong")).status).toBe(401);
  });

  it("keeps empty and Blob outage states distinct", async () => {
    testMocks.latestCommand.mockResolvedValueOnce(null);
    await expect((await latestCommandResponse("Bearer read-token")).json()).resolves.toMatchObject({
      status: "empty",
    });
    testMocks.latestCommand.mockRejectedValueOnce(new Error("Blob unavailable"));
    const failure = await latestCommandResponse("Bearer read-token");
    expect(failure.status).toBe(503);
    await expect(failure.json()).resolves.toEqual({
      status: "error",
      error: "blob_unavailable",
    });
  });
});
