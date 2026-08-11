import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({
  saveLatestCommand: vi.fn(),
}));

vi.mock("../src/blob.js", () => ({
  saveLatestCommand: testMocks.saveLatestCommand,
}));

import { handler } from "../src/mcp.js";

const CAPABILITY = "a".repeat(43);

function mcpRequest(accept: string, body: unknown): Request {
  return new Request(
    "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY,
    {
      method: "POST",
      headers: {
        Accept: accept,
        "Content-Type": "application/json",
      },
      body: JSON.stringify(body),
    },
  );
}

function call(request: Request): Promise<Response> {
  return handler(request, CAPABILITY);
}

function saveCall(id: number, command: string, modern = false): Request {
  return new Request(
    "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY,
    {
      method: "POST",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        ...(modern ? { "MCP-Protocol-Version": "2026-07-28" } : {}),
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id,
        method: "tools/call",
        params: {
          name: "save_selfrun_command",
          arguments: { command },
          ...(modern
            ? {
                _meta: {
                  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                  "io.modelcontextprotocol/clientCapabilities": {},
                  "io.modelcontextprotocol/clientInfo": {
                    name: "test-client",
                    version: "1.0.0",
                  },
                },
              }
            : {}),
        },
      }),
    },
  );
}

describe("SelfRun MCP transport negotiation", () => {
  beforeEach(() => {
    process.env.SELF_RUN_MCP_CAPABILITY = CAPABILITY;
    testMocks.saveLatestCommand.mockReset();
    testMocks.saveLatestCommand.mockResolvedValue({
      savedAt: "2026-08-12T00:00:00.000Z",
      hash: "verified-hash",
    });
  });

  it("accepts a JSON-only client without exposing OAuth security metadata", async () => {
    const response = await call(
      mcpRequest("application/json", {
        jsonrpc: "2.0",
        id: 1,
        method: "tools/list",
        params: {},
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    const payload = await response.json();
    const saveTool = payload.result.tools.find(
      (tool: { name: string }) => tool.name === "save_selfrun_command",
    );
    expect(saveTool.securitySchemes).toBeUndefined();
    expect(JSON.stringify(saveTool).toLowerCase()).not.toContain("oauth");
    expect(JSON.stringify(saveTool)).not.toContain("commands:write");
  });

  it("keeps the standard dual Accept header interoperable and saves", async () => {
    const response = await call(saveCall(2, "test command"));
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(payload.result.structuredContent).toMatchObject({
      saved_at: "2026-08-12T00:00:00.000Z",
      hash: "verified-hash",
    });
    expect(testMocks.saveLatestCommand).toHaveBeenCalledWith("test command");
  });

  it("normalizes a legacy non-JSON content type for a JSON body", async () => {
    const request = new Request(
      "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY,
      {
        method: "POST",
        headers: { Accept: "application/json", "Content-Type": "text/plain" },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 3,
          method: "tools/list",
          params: {},
        }),
      },
    );
    const response = await call(request);
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
  });

  it("serves the v2 modern envelope with a JSON response", async () => {
    const response = await call(
      new Request(
        "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY,
        {
          method: "POST",
          headers: {
            Accept: "application/json",
            "Content-Type": "application/json",
            "Mcp-Method": "server/discover",
            "MCP-Protocol-Version": "2026-07-28",
          },
          body: JSON.stringify({
            jsonrpc: "2.0",
            id: 4,
            method: "server/discover",
            params: {
              _meta: {
                "io.modelcontextprotocol/protocolVersion": "2026-07-28",
                "io.modelcontextprotocol/clientCapabilities": {},
                "io.modelcontextprotocol/clientInfo": {
                  name: "test-client",
                  version: "1.0.0",
                },
              },
            },
          }),
        },
      ),
    );
    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain("application/json");
    const payload = await response.json();
    expect(payload.result.supportedVersions).toContain("2026-07-28");
  });

  it("fills missing modern standard headers from the JSON-RPC body", async () => {
    const response = await call(saveCall(5, "modern header synthesis", true));
    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(testMocks.saveLatestCommand).toHaveBeenCalledWith(
      "modern header synthesis",
    );
  });

  it("preserves an existing mismatched Mcp-Method header", async () => {
    const request = saveCall(6, "test", true);
    request.headers.set("Mcp-Method", "tools/list");
    const response = await call(request);
    expect(response.status).toBe(400);
  });

  it("preserves an existing mismatched Mcp-Name header", async () => {
    const request = saveCall(7, "test", true);
    request.headers.set("Mcp-Method", "tools/call");
    request.headers.set("Mcp-Name", "different_tool");
    const response = await call(request);
    expect(response.status).toBe(400);
  });

  it("downgrades a claim-less modern protocol header to the legacy path", async () => {
    const request = saveCall(8, "legacy header downgrade");
    request.headers.set("MCP-Protocol-Version", "2026-07-28");
    request.headers.set("Mcp-Method", "tools/call");
    request.headers.set("Mcp-Name", "save_selfrun_command");
    const response = await call(request);
    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(testMocks.saveLatestCommand).toHaveBeenCalledWith(
      "legacy header downgrade",
    );
  });

  it("preserves a modern header/body protocol-version mismatch", async () => {
    const request = saveCall(9, "test", true);
    request.headers.set("MCP-Protocol-Version", "2025-11-25");
    const response = await call(request);
    expect(response.status).toBe(400);
  });

  it("blocks a missing or wrong capability with an opaque 404", async () => {
    const request = saveCall(10, "must not save");
    const missing = await handler(request);
    expect(missing.status).toBe(404);
    expect(await missing.text()).toBe("");

    const wrong = await handler(request, "b".repeat(43));
    expect(wrong.status).toBe(404);
    expect(await wrong.text()).toBe("");
    expect(testMocks.saveLatestCommand).not.toHaveBeenCalled();
  });
});
