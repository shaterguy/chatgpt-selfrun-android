import { describe, expect, it } from "vitest";

import { handler } from "../src/mcp.js";

function mcpRequest(accept: string, body: unknown): Request {
  return new Request("https://selfrun-command-bridge.vercel.app/mcp", {
    method: "POST",
    headers: {
      Accept: accept,
      "Content-Type": "application/json",
    },
    body: JSON.stringify(body),
  });
}

describe("SelfRun MCP transport negotiation", () => {
  it("accepts a JSON-only client and returns a JSON tools/list response", async () => {
    const response = await handler(
      mcpRequest("application/json", {
        jsonrpc: "2.0",
        id: 1,
        method: "tools/list",
        params: {},
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(
      "application/json",
    );

    const payload = await response.json();
    const saveTool = payload.result.tools.find(
      (tool: { name: string }) => tool.name === "save_selfrun_command",
    );
    expect(saveTool.securitySchemes).toEqual([
      { type: "oauth2", scopes: ["commands:write"] },
    ]);
  });

  it("keeps the standard dual Accept header interoperable", async () => {
    const response = await handler(
      mcpRequest("application/json, text/event-stream", {
        jsonrpc: "2.0",
        id: 2,
        method: "tools/call",
        params: {
          name: "save_selfrun_command",
          arguments: { command: "test command" },
        },
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(
      "application/json",
    );
    const payload = await response.json();
    expect(payload.result.isError).toBe(true);
    expect(payload.result._meta["mcp/www_authenticate"][0]).toContain(
      "resource_metadata=",
    );
  });

  it("normalizes a legacy non-JSON content type for a JSON body", async () => {
    const request = new Request(
      "https://selfrun-command-bridge.vercel.app/mcp",
      {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "text/plain",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 3,
          method: "tools/list",
          params: {},
        }),
      },
    );
    const response = await handler(request);

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(
      "application/json",
    );
  });

  it("serves the v2 modern envelope with a JSON response", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "Mcp-Method": "server/discover",
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
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(
      "application/json",
    );
    const payload = await response.json();
    expect(payload.result.supportedVersions).toContain("2026-07-28");
  });
});
