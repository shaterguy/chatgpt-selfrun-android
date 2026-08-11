import {
  createMcpHandler,
  isLegacyRequest,
  McpServer,
  WebStandardStreamableHTTPServerTransport,
} from "@modelcontextprotocol/server";
import { z } from "zod";

import { hasValidMcpCapability, notFoundResponse } from "./capability.js";
import { saveLatestCommand } from "./blob.js";
import { validateCommand } from "./command.js";

const SERVER_INFO = {
  name: "SelfRun Command Bridge",
  version: "0.1.1-dev1",
} as const;

function registerTools(server: McpServer): void {
  server.registerTool(
    "save_selfrun_command",
    {
      title: "Save SelfRun command",
      description:
        "Save one complete SelfRun command and return its timestamp and SHA-256 hash.",
      inputSchema: z.object({
        command: z.string().min(1),
      }),
    },
    async ({ command }) => {
      try {
        validateCommand(command);
        const saved = await saveLatestCommand(command);
        const result = {
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
}

function createServer(): McpServer {
  const server = new McpServer(SERVER_INFO);
  registerTools(server);
  return server;
}

// Modern 2026-07-28 traffic is handled by the v2 SDK directly so the
// responseMode option is available. The legacy leg is routed below because
// the SDK's built-in stateless fallback intentionally defaults to SSE, while
// the existing ChatGPT connector expects a JSON response body.
const modernHandler = createMcpHandler(
  async () => createServer(),
  {
    legacy: "reject",
    responseMode: "auto",
  },
);

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

function normalizeMcpRequest(request: Request, body: string): Request {
  const originalAccept = (request.headers.get("accept") ?? "").toLowerCase();
  const acceptsJson =
    originalAccept.includes("application/json") ||
    originalAccept.includes("application/*") ||
    originalAccept.includes("*/*");
  const acceptsEventStream =
    originalAccept.includes("text/event-stream") ||
    originalAccept.includes("text/*") ||
    originalAccept.includes("*/*");

  const headers = new Headers(request.headers);
  if (!originalAccept || acceptsJson || acceptsEventStream) {
    headers.set("Accept", "application/json, text/event-stream");
  }
  if (!headers.get("Content-Type")?.toLowerCase().includes("application/json")) {
    headers.set("Content-Type", "application/json");
  }

  const normalized = new Request(request.url, {
    method: "POST",
    headers,
    body,
  });
  return normalized;
}

const MCP_PROTOCOL_VERSION_HEADER = "MCP-Protocol-Version";
const MCP_PROTOCOL_VERSION_META_KEY =
  "io.modelcontextprotocol/protocolVersion";
const FIRST_MODERN_PROTOCOL_VERSION = "2026-07-28";

function isRecord(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === "object" && !Array.isArray(value);
}

function hasModernProtocolEnvelopeClaim(body: unknown): boolean {
  if (!isRecord(body) || !isRecord(body.params)) return false;
  const meta = body.params._meta;
  return isRecord(meta) &&
    Object.prototype.hasOwnProperty.call(meta, MCP_PROTOCOL_VERSION_META_KEY);
}

function isClaimlessRequestMessage(body: unknown): boolean {
  if (!isRecord(body) || typeof body.method !== "string") return false;
  if (body.method === "initialize") return false;
  if (!Object.prototype.hasOwnProperty.call(body, "id")) return false;
  return !hasModernProtocolEnvelopeClaim(body);
}

function hasModernProtocolVersionHeader(request: Request): boolean {
  const protocolVersion = request.headers
    .get(MCP_PROTOCOL_VERSION_HEADER)
    ?.trim();
  return (
    protocolVersion !== undefined &&
    /^\d{4}-\d{2}-\d{2}$/.test(protocolVersion) &&
    protocolVersion >= FIRST_MODERN_PROTOCOL_VERSION
  );
}

function normalizeClaimlessLegacyProtocolHeader(
  request: Request,
  body: string,
  parsedBody: unknown,
): Request {
  if (
    !isClaimlessRequestMessage(parsedBody) ||
    !hasModernProtocolVersionHeader(request)
  ) {
    return request;
  }

  const headers = new Headers(request.headers);
  headers.delete(MCP_PROTOCOL_VERSION_HEADER);
  const normalized = new Request(request.url, {
    method: "POST",
    headers,
    body,
  });
  return normalized;
}


type McpRoute = "legacy" | "modern";
function mcpProtocolVersionState(
  request: Request,
): "absent" | "2026-07-28" | "other_date" | "invalid" {
  const value = request.headers
    .get(MCP_PROTOCOL_VERSION_HEADER)
    ?.trim();
  if (!value) return "absent";
  if (value === FIRST_MODERN_PROTOCOL_VERSION) return "2026-07-28";
  if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return "other_date";
  return "invalid";
}

function mcpBodyMethod(body: unknown): string {
  if (!isRecord(body) || typeof body.method !== "string") return "unknown";
  const knownMethods = new Set([
    "server/discover",
    "initialize",
    "tools/list",
    "tools/call",
    "prompts/get",
    "resources/read",
  ]);
  return knownMethods.has(body.method) ? body.method : "other";
}

function logMcpTransportDiagnostic(
  details: Record<string, unknown>,
): void {
  console.info("mcp_transport", JSON.stringify(details));
}

async function classifyMcpResponse(response: Response): Promise<string> {
  if (response.status < 400) return "ok";

  const contentType = response.headers.get("content-type")?.toLowerCase() ?? "";
  if (!contentType.includes("application/json")) {
    return `http_${response.status}_non_json`;
  }

  let payload: unknown;
  try {
    payload = await response.clone().json();
  } catch {
    return `http_${response.status}_unparseable_json`;
  }

  const error = isRecord(payload) && isRecord(payload.error)
    ? payload.error
    : undefined;
  const code = error?.code;
  const message =
    typeof error?.message === "string"
      ? error.message.toLowerCase()
      : "";

  if (message.includes("envelope") || message.includes("_meta")) {
    return "modern_envelope_validation";
  }
  if (message.includes("header") || message.includes("mcp-")) {
    return "request_header_validation";
  }
  if (message.includes("protocol")) {
    return "protocol_validation";
  }
  if (typeof code === "number") return `jsonrpc_${code}`;
  return `http_${response.status}`;
}


const MCP_NAME_HEADER_SOURCES = {
  "tools/call": "name",
  "prompts/get": "name",
  "resources/read": "uri",
} as const;

function normalizeModernRequestHeaders(
  request: Request,
  body: string,
  parsedBody: unknown,
): Request {
  if (
    parsedBody === null ||
    typeof parsedBody !== "object" ||
    Array.isArray(parsedBody)
  ) {
    return request;
  }

  const message = parsedBody as {
    method?: unknown;
    params?: unknown;
  };
  const headers = new Headers(request.headers);

  if (!headers.has("Mcp-Method") && typeof message.method === "string") {
    headers.set("Mcp-Method", message.method);
  }

  const sourceField =
    typeof message.method === "string"
      ? MCP_NAME_HEADER_SOURCES[
          message.method as keyof typeof MCP_NAME_HEADER_SOURCES
        ]
      : undefined;
  const params =
    message.params !== null &&
    typeof message.params === "object" &&
    !Array.isArray(message.params)
      ? (message.params as Record<string, unknown>)
      : undefined;
  const sourceValue = sourceField === undefined ? undefined : params?.[sourceField];

  if (!headers.has("Mcp-Name") && typeof sourceValue === "string") {
    headers.set("Mcp-Name", sourceValue);
  }

  const normalized = new Request(request.url, {
    method: "POST",
    headers,
    body,
  });
  return normalized;
}

async function handleLegacyRequest(
  request: Request,
): Promise<Response> {
  const server = createServer();
  const transport = new WebStandardStreamableHTTPServerTransport({
    sessionIdGenerator: undefined,
    enableJsonResponse: true,
  });

  try {
    await server.connect(transport);
    return await transport.handleRequest(request);
  } finally {
    await transport.close().catch(() => undefined);
    await server.close().catch(() => undefined);
  }
}

async function handleMcpRequest(request: Request): Promise<Response> {
  if (request.method !== "POST") {
    return mcpErrorResponse("Method not allowed.", 405);
  }

  const body = await request.text();
  let parsedBody: unknown;
  try {
    parsedBody = JSON.parse(body);
  } catch {
    parsedBody = undefined;
  }

  logMcpTransportDiagnostic({
    event: "request",
    protocolVersion: mcpProtocolVersionState(request),
    sessionHeaderPresent: request.headers.has("mcp-session-id"),
    bodyMethod: mcpBodyMethod(parsedBody),
  });

  const normalized = normalizeMcpRequest(request, body);
  const compatibilityNormalized = normalizeClaimlessLegacyProtocolHeader(
    normalized,
    body,
    parsedBody,
  );
  const legacy = await isLegacyRequest(compatibilityNormalized, parsedBody);
  const route: McpRoute = legacy ? "legacy" : "modern";

  try {
    const response = legacy
      ? await handleLegacyRequest(compatibilityNormalized)
      : await modernHandler.fetch(
        normalizeModernRequestHeaders(
          compatibilityNormalized,
          body,
          parsedBody,
        ),
      );

    logMcpTransportDiagnostic({
      event: "response",
      route,
      status: response.status,
      category: await classifyMcpResponse(response),
    });
    return response;
  } catch (error) {
    logMcpTransportDiagnostic({
      event: "exception",
      route,
      errorName: error instanceof Error ? error.name : "unknown",
    });
    throw error;
  }
}

export async function handler(
  request: Request,
  capability?: string,
): Promise<Response> {
  if (!hasValidMcpCapability(capability)) return notFoundResponse();
  return handleMcpRequest(request);
}
