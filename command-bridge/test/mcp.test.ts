import { beforeEach, describe, expect, it, vi } from "vitest";

const testMocks = vi.hoisted(() => ({
  verifyAccessToken: vi.fn(),
  saveLatestCommand: vi.fn(),
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
        "Bearer resource_metadata=\"https://selfrun-command-bridge.vercel.app/.well-known/oauth-protected-resource\", error=\"" +
          error +
          "\"",
      ],
    },
  }),
}));

vi.mock("../src/blob.js", () => ({
  saveLatestCommand: testMocks.saveLatestCommand,
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

describe("SelfRun MCP OAuth and transport contract", () => {
  beforeEach(() => {
    process.env.AUTH0_ISSUER = "https://tenant.example.com/";
    process.env.AUTH0_AUDIENCE = "https://selfrun-command-bridge.vercel.app";
    process.env.AUTH0_ALLOWED_SUB = "auth0|allowed";
    testMocks.verifyAccessToken.mockReset();
    testMocks.verifyAccessToken.mockImplementation(
      async (_request: Request, bearerToken?: string) =>
        bearerToken === "test-token" ? testMocks.authInfo : undefined,
    );
    testMocks.saveLatestCommand.mockReset();
    testMocks.saveLatestCommand.mockResolvedValue({
      savedAt: "2026-08-12T00:00:00.000Z",
      hash: "verified-hash",
    });
  });

  it("advertises OAuth security metadata in tools/list", async () => {
    const response = await handler(
      mcpRequest("application/json", {
        jsonrpc: "2.0",
        id: 1,
        method: "tools/list",
        params: {},
      }),
    );

    expect(response.status).toBe(200);
    const payload = await response.json();
    const saveTool = payload.result.tools.find(
      (tool: { name: string }) => tool.name === "save_selfrun_command",
    );
    expect(saveTool.securitySchemes).toEqual([
      { type: "oauth2", scopes: ["commands:write"] },
    ]);
  });

  it("returns a tool-level OAuth challenge when the save call is anonymous", async () => {
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
    const payload = await response.json();
    expect(payload.result.isError).toBe(true);
    expect(payload.result._meta["mcp/www_authenticate"][0]).toContain(
      "resource_metadata=",
    );
    expect(testMocks.saveLatestCommand).not.toHaveBeenCalled();
  });

  it("returns an HTTP OAuth challenge for an invalid bearer token", async () => {
    testMocks.verifyAccessToken.mockResolvedValueOnce(undefined);
    const response = await handler(
      authenticatedMcpRequest({
        jsonrpc: "2.0",
        id: 3,
        method: "tools/call",
        params: {
          name: "save_selfrun_command",
          arguments: { command: "must not be stored" },
        },
      }),
    );

    expect(response.status).toBe(401);
    expect(response.headers.get("www-authenticate")).toContain(
      "resource_metadata=",
    );
    expect(testMocks.saveLatestCommand).not.toHaveBeenCalled();
  });

  it("saves an authenticated modern request", async () => {
    const response = await handler(
      authenticatedMcpRequest(
        {
          jsonrpc: "2.0",
          id: 4,
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
      saved_at: "2026-08-12T00:00:00.000Z",
      hash: "verified-hash",
    });
    expect(testMocks.saveLatestCommand).toHaveBeenCalledWith(
      "modern authenticated command",
    );
  });

  it("saves an authenticated claim-less legacy request", async () => {
    const response = await handler(
      authenticatedMcpRequest(
        {
          jsonrpc: "2.0",
          id: 5,
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
    expect(testMocks.saveLatestCommand).toHaveBeenCalledWith(
      "legacy authenticated command",
    );
  });

  it("keeps the modern header/body mismatch visible", async () => {
    const response = await handler(
      authenticatedMcpRequest(
        {
          jsonrpc: "2.0",
          id: 6,
          method: "tools/call",
          params: {
            name: "save_selfrun_command",
            arguments: { command: "test" },
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
        { "MCP-Protocol-Version": "2025-11-25" },
      ),
    );

    expect(response.status).toBe(400);
    expect(testMocks.saveLatestCommand).not.toHaveBeenCalled();
  });

  it("rejects a verified token without the write scope at the HTTP boundary", async () => {
    testMocks.verifyAccessToken.mockResolvedValueOnce({
      ...testMocks.authInfo,
      scopes: [],
    });

    const response = await handler(
      authenticatedMcpRequest({
        jsonrpc: "2.0",
          id: 7,
        method: "tools/call",
        params: {
          name: "save_selfrun_command",
          arguments: { command: "must not be stored" },
        },
      }),
    );

    expect(response.status).toBe(403);
    expect(response.headers.get("www-authenticate")).toContain(
      "commands:write",
    );
    expect(testMocks.saveLatestCommand).not.toHaveBeenCalled();
  });
});
