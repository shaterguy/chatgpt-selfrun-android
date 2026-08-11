import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({
  get: vi.fn(),
  head: vi.fn(),
  put: vi.fn(),
}));

vi.mock("@vercel/blob", () => testMocks);

import {
  LATEST_COMMAND_PATHNAME,
  latestCommand,
  probeBlob,
  saveLatestCommand,
} from "../src/blob.js";
import { commandHash, MAX_COMMAND_BYTES } from "../src/command.js";

describe("SelfRun latest Blob slot", () => {
  beforeEach(() => {
    testMocks.get.mockReset();
    testMocks.head.mockReset();
    testMocks.put.mockReset();
  });

  it("checks readiness with Blob metadata without reading the command body", async () => {
    testMocks.head.mockResolvedValueOnce({ etag: "etag" });
    await expect(probeBlob()).resolves.toBe("ok");
    expect(testMocks.head).toHaveBeenCalledWith(LATEST_COMMAND_PATHNAME);
    expect(testMocks.get).not.toHaveBeenCalled();
  });

  it("preserves complex command bytes and uses the fixed private overwrite path", async () => {
    const command = [
      "한글",
      "```json",
      '{"quote":"\\\"","slash":"\\\\","emoji":"🧪"}',
      "```",
      "마지막 줄",
    ].join("\n");
    await saveLatestCommand(command, "2026-08-12T00:00:00.000Z");
    expect(testMocks.put).toHaveBeenCalledWith(
      LATEST_COMMAND_PATHNAME,
      JSON.stringify({
        command,
        saved_at: "2026-08-12T00:00:00.000Z",
        hash: commandHash(command),
      }),
      {
        access: "private",
        addRandomSuffix: false,
        allowOverwrite: true,
        contentType: "application/json; charset=utf-8",
      },
    );
  });

  it("reads the newest overwritten value with cache disabled", async () => {
    const command = "second\\nvalue";
    testMocks.get.mockResolvedValueOnce({
      statusCode: 200,
      stream: new ReadableStream({
        start(controller) {
          controller.enqueue(
            new TextEncoder().encode(
              JSON.stringify({
                command,
                saved_at: "2026-08-12T00:00:01.000Z",
                hash: commandHash(command),
              }),
            ),
          );
          controller.close();
        },
      }),
    });
    await expect(latestCommand()).resolves.toEqual({
      command,
      savedAt: "2026-08-12T00:00:01.000Z",
      hash: commandHash(command),
    });
    expect(testMocks.get).toHaveBeenCalledWith(LATEST_COMMAND_PATHNAME, {
      access: "private",
      useCache: false,
    });
  });

  it("rejects commands over the UTF-8 1 MiB limit without writing", async () => {
    const oversized = "가".repeat(Math.ceil(MAX_COMMAND_BYTES / 3));
    await expect(saveLatestCommand(oversized)).rejects.toThrow("1 MiB");
    expect(testMocks.put).not.toHaveBeenCalled();
  });
});
