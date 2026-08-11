import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { ListToolsRequestSchema } from "@modelcontextprotocol/sdk/types.js";
import { WebStandardStreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/webStandardStreamableHttp.js";
import { withMcpAuth } from "mcp-handler";
import { z } from "zod";

import { oauthChallenge, verifyAccessToken } from "./auth.js";
import { commandHash, validateCommand } from "./command.js";
import { getRuntimeConfig } from "./config.js";
import { insertCommand } from "./db.js";

const WRITE_SCOPE = "commands:write";

function registerTools(server: any): void {
  server.registerTool(
    "save_selfrun_command",
    {
      title: "Save SelfRun command",
      description:
        "Save one complete SelfRun command and return its durable ID, timestamp, and SHA-256 hash.",
      inputSchema: {
        command: z.string().min(1),
      },
      _meta: {
        securitySchemes: [{ type: "oauth2", scopes: [WRITE_SCOPE] }],
      },
    },
    async ({ command }: { command: string }, extra: any) => {
      const authInfo = extra?.authInfo;
      if (!authInfo) {
        return oauthChallenge(
          "Authentication required: connect the Command Bridge with OAuth before saving.",
        );
      }
      if (!authInfo.scopes?.includes(WRITE_SCOPE)) {
        return oauthChallenge(
          "Insufficient scope: " + WRITE_SCOPE + " is required.",
        );
      }

      try {
        validateCommand(command);
        const saved = await insertCommand(command, commandHash(command));
        const result = {
          command_id: saved.commandId,
          saved_at: saved.savedAt,
          hash: saved.hash,
        };
        return {
          structuredContent: result,
          content: [{ type: "text", text: JSON.stringify(result) }],
        };
      } catch (error) {
        if (error instanceof Error && error.message.includes("1 MiB")) {
          return {
            content: [{ type: "text", text: "Command is larger than 1 MiB." }],
            isError: true,
          };
        }
        return {
          content: [{ type: "text", text: "Command storage is unavailable." }],
          isError: true,
        };
      }
    },
  );

  // The SDK version used by mcp-handler exposes custom metadata under _meta,
  // while ChatGPT's current connector contract reads securitySchemes from the
  // tool definition itself. Wrap the SDK's generated tools/list response so
  // both surfaces remain available.
  const underlying = server.server as any;
  const originalListHandler = underlying._requestHandlers?.get("tools/list");
  if (originalListHandler) {
    underlying.setRequestHandler(
      ListToolsRequestSchema,
      async (request: any, extra: any) => {
        const result = await originalListHandler(request, extra);
        return {
          ...result,
          tools: result.tools.map((tool: Record<string, unknown>) =>
            tool.name === "save_selfrun_command"
              ? {
                  ...tool,
                  securitySchemes: [
                    { type: "oauth2", scopes: [WRITE_SCOPE] },
                  ],
                }
              : tool,
          ),
        };
      },
    );
  }
}

const config = getRuntimeConfig();

function mcpErrorResponse(message: string, status: number): Response {
  return new Response(
    JSON.stringify({
      jsonrpc: "2.0",
      error: { code: -32000, message },
      id: null,
    }),
    {
      status,
      headers: { "Content-Type": "application/json" },
    },
  );
}

function normalizedMcpRequest(
  request: Request,
  body: string,
): Request {
  const originalAccept = (request.headers.get("accept") ?? "").toLowerCase();
  const acceptsJson =
    originalAccept.includes("application/json") ||
    originalAccept.includes("application/*") ||
    originalAccept.includes("*/*");
  const acceptsEventStream =
    originalAccept.includes("text/event-stream") ||
    originalAccept.includes("text/*") ||
    originalAccept.includes("*/*");

  // The MCP SDK validates the standard dual Accept header before it reaches
  // the handler. ChatGPT's connected tool currently sends application/json
  // only, so normalize JSON-capable legacy clients to the standard header.
  const headers = new Headers(request.headers);
  if (!originalAccept || acceptsJson || acceptsEventStream) {
    headers.set("Accept", "application/json, text/event-stream");
  }
  if (!headers.get("Content-Type")?.toLowerCase().includes("application/json")) {
    headers.set("Content-Type", "application/json");
  }

  return new Request(request.url, {
    method: "POST",
    headers,
    body,
  });
}

async function handleMcpRequest(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return mcpErrorResponse("Method not allowed.", 405);
  }

  const body = await request.text();
  const normalized = normalizedMcpRequest(request, body);
  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
  });
  const server = new McpServer({
    name: "SelfRun Command Bridge",
    version: "0.1.0",
  });
  registerTools(server);

  await server.connect(transport);
  try {
    return await transport.handleRequest(normalized, {
      authInfo: (request as Request & { auth?: unknown }).auth as any,
    });
  } finally {
    await server.close();
  }
}

export const handler = withMcpAuth(handleMcpRequest, verifyAccessToken, {
  required: false,
  resourceUrl: config.mcpResourceUrl,
});
