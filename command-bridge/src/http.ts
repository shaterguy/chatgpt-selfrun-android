import type {
  IncomingHttpHeaders,
  IncomingMessage,
  ServerResponse,
} from "node:http";

export const NO_STORE_HEADERS = {
  "Cache-Control": "private, no-store, max-age=0, must-revalidate",
  "CDN-Cache-Control": "no-store",
  "Vercel-CDN-Cache-Control": "no-store",
  Pragma: "no-cache",
  Vary: "Authorization",
};

export function jsonResponse(
  body: unknown,
  status = 200,
  headers: Record<string, string> = {},
): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      "Content-Type": "application/json; charset=utf-8",
      ...headers,
    },
  });
}

export function setJson(
  response: ServerResponse,
  body: unknown,
  status: number,
  headers: Record<string, string> = {},
): void {
  response.statusCode = status;
  response.setHeader("Content-Type", "application/json; charset=utf-8");
  for (const [name, value] of Object.entries(headers)) {
    response.setHeader(name, value);
  }
  response.end(JSON.stringify(body));
}

function headerValue(
  headers: IncomingHttpHeaders,
  name: string,
): string | undefined {
  const value = headers[name];
  return Array.isArray(value) ? value[0] : value;
}

export function publicRequestUrl(request: IncomingMessage): string {
  const forwardedProto = headerValue(request.headers, "x-forwarded-proto");
  const forwardedHost = headerValue(request.headers, "x-forwarded-host");
  const protocol = forwardedProto?.split(",")[0]?.trim() || "https";
  const host =
    forwardedHost?.split(",")[0]?.trim() ||
    headerValue(request.headers, "host") ||
    "selfrun-command-bridge.vercel.app";
  return new URL(request.url || "/", protocol + "://" + host).toString();
}

export async function readRequestBody(
  request: IncomingMessage,
  maxBytes = 2 * 1024 * 1024,
): Promise<Buffer> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buffer.length;
    if (total > maxBytes) {
      throw new Error("request body too large");
    }
    chunks.push(buffer);
  }
  return Buffer.concat(chunks);
}
