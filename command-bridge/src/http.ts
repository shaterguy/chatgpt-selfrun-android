import type { IncomingMessage, ServerResponse } from "node:http";

export async function readJson(request: IncomingMessage, maxBytes = 16_384): Promise<unknown> {
  const chunks: Buffer[] = [];
  let total = 0;
  for await (const chunk of request) {
    const buffer = Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk);
    total += buffer.length;
    if (total > maxBytes) throw new HttpError(413, "request too large");
    chunks.push(buffer);
  }
  if (total === 0) throw new HttpError(400, "JSON body required");
  try {
    return JSON.parse(Buffer.concat(chunks).toString("utf8"));
  } catch {
    throw new HttpError(400, "invalid JSON");
  }
}

export class HttpError extends Error {
  constructor(public readonly status: number, message: string) { super(message); }
}

export function json(response: ServerResponse, status: number, body: unknown): void {
  response.statusCode = status;
  response.setHeader("content-type", "application/json; charset=utf-8");
  response.setHeader("cache-control", "no-store, max-age=0");
  response.end(JSON.stringify(body));
}

export function empty(response: ServerResponse, status = 204): void {
  response.statusCode = status;
  response.setHeader("cache-control", "no-store, max-age=0");
  response.end();
}

export function method(request: IncomingMessage, expected: string): void {
  if (request.method !== expected) throw new HttpError(405, "method not allowed");
}

export function header(request: IncomingMessage, name: string, max = 512): string {
  const raw = request.headers[name.toLowerCase()];
  const value = Array.isArray(raw) ? raw[0] : raw;
  if (typeof value !== "string" || value.length === 0 || value.length > max || /[\0\r\n]/.test(value)) {
    throw new HttpError(400, `${name} required`);
  }
  return value;
}

export function errorResponse(response: ServerResponse, error: unknown): void {
  if (error instanceof HttpError) return json(response, error.status, { ok: false, error: error.message });
  const message = error instanceof Error ? error.message : "internal error";
  return json(response, 500, { ok: false, error: message.slice(0, 300) });
}
