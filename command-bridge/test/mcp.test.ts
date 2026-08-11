import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({
  verifyAccessToken: vi.fn(),
  insertCommand: vi.fn(),
  authInfo: {
    token: "test-token",
    clientId: "test-client",
    scopes: ["commands:write"],
    expiresAt: Math.floor(Date.now() / 1000) + 3600,
    resource: new URL("https://selfrun-command-bridge.vercel.app"),
  },
}));

vi.mock("../src/auth.js", () => ({
  verifyAccessToken: testMocks.verifyAccessToken,
  oauthChallenge: (
    message: string,
    error: "invalid_token" | "insufficient_scope" = "insufficient_scope",
  ) => ({
    content: [{ type: "text", text: message }],
    isError: true,
    _meta: {
      "mcp/www_authenticate": [
        `Bearer resource_metadata="https://selfrun-command-bridge.vercel.app/.well-known/oauth-protected-resource", error="${error}"`,
      ],
    },
  }),
}));

vi.mock("../src/db.js", () => ({
  insertCommand: testMocks.insertCommand,
}));

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

function authenticatedMcpRequest(
  body: unknown,
  extraHeaders: Record<string, string> = {},
): Request {
  return new Request("https://selfrun-command-bridge.vercel.app/mcp", {
    method: "POST",
    headers: {
      Accept: "application/json",
      "Content-Type": "application/json",
      Authorization: "Bearer test-token",
      ...extraHeaders,
    },
    body: JSON.stringify(body),
  });
}

describe("SelfRun MCP transport negotiation", () => {
  beforeEach(() => {
    testMocks.verifyAccessToken.mockReset();
    testMocks.verifyAccessToken.mockImplementation(
      async (_request: Request, bearerToken?: string) =>
        bearerToken === "test-token" ? testMocks.authInfo : undefined,
    );
    testMocks.insertCommand.mockReset();
    testMocks.insertCommand.mockResolvedValue({
      commandId: "verified-command",
      savedAt: "2026-08-11T00:00:00.000Z",
      hash: "verified-hash",
    });
  });
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
      }),
    );

    expect(response.status).toBe(200);
    expect(response.headers.get("content-type")).toContain(
      "application/json",
    );
    const payload = await response.json();
    expect(payload.result.supportedVersions).toContain("2026-07-28");
  });

  it("fills missing modern standard headers from the JSON-RPC body", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 5,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "test command" },
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
    const payload = await response.json();
    expect(payload.result.isError).toBe(true);
    expect(payload.result._meta["mcp/www_authenticate"][0]).toContain(
      "resource_metadata=",
    );
  });

  it("preserves an existing mismatched Mcp-Method header", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "Mcp-Method": "tools/list",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 6,
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

    expect(response.status).toBe(400);
  });

  it("preserves an existing mismatched Mcp-Name header", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "Mcp-Method": "tools/call",
          "Mcp-Name": "different_tool",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 7,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "test command" },
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

    expect(response.status).toBe(400);
  });

  it("downgrades a claim-less modern protocol header to the legacy path", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "MCP-Protocol-Version": "2026-07-28",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 8,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "test command" },
          },
        }),
      }),
    );

    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).toBe(true);
    expect(payload.result._meta["mcp/www_authenticate"][0]).toContain(
      "resource_metadata=",
    );
  });

  it("preserves a modern header/body protocol-version mismatch", async () => {
    const response = await handler(
      new Request("https://selfrun-command-bridge.vercel.app/mcp", {
        method: "POST",
        headers: {
          Accept: "application/json",
          "Content-Type": "application/json",
          "MCP-Protocol-Version": "2025-11-25",
        },
        body: JSON.stringify({
          jsonrpc: "2.0",
          id: 9,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "test command" },
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

    expect(response.status).toBe(400);
  });
  it("passes verified auth context through the modern handler path", async () => {
    const response = await handler(
      authenticatedMcpRequest(
        {
          jsonrpc: "2.0",
          id: 10,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "modern authenticated command" },
            _meta: {
  "io.modelcontextprotocol/protocolVersion": "2026-07-28",
  "io.modelcontextprotocol/clientCapabilities": {},
  "io.modelcontextprotocol/clientInfo": {
    name: "test-client",
    version: "1.0.0",
  },
},
          },
        },
        { "MCP-Protocol-Version": "2026-07-28" },
      ),
    );

    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(payload.result.structuredContent).toMatchObject({
      command_id: "verified-command",
    });
    expect(testMocks.insertCommand).toHaveBeenCalledWith(
      "modern authenticated command",
      expect.any(String),
    );
  });

  it("passes verified auth context through the claim-less legacy path", async () => {
    const response = await handler(
      authenticatedMcpRequest(
        {
          jsonrpc: "2.0",
          id: 11,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "legacy authenticated command" },
          },
        },
        {
          "Mcp-Method": "tools/call",
          "Mcp-Name": "save_selfrun_command",
          "MCP-Protocol-Version": "2026-07-28",
        },
      ),
    );

    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(payload.result.structuredContent).toMatchObject({
      command_id: "verified-command",
    });
    expect(testMocks.insertCommand).toHaveBeenCalledWith(
      "legacy authenticated command",
      expect.any(String),
    );
  });

  it("returns a 403 scope challenge before the authenticated MCP handler", async () => {
    testMocks.verifyAccessToken.mockResolvedValueOnce({
      ...testMocks.authInfo,
      scopes: [],
    });

    const response = await handler(
      authenticatedMcpRequest({
        jsonrpc: "2.0",
        id: 12,
        method: "tools/call",
        params: {
          name: "save_selfrun_command",
          arguments: { command: "should not be stored" },
        },
      }),
    );

    expect(response.status).toBe(403);
    expect(response.headers.get("www-authenticate")).toContain(
      'scope="commands:write"',
    );
    expect(testMocks.insertCommand).not.toHaveBeenCalled();
  });

});
