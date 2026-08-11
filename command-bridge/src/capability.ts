import { createHash, timingSafeEqual } from "node:crypto";

import { getRuntimeConfig } from "./config.js";

const CAPABILITY_PATTERN = /^[A-Za-z0-9_-]{43}$/;

function digest(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

export function isMcpCapabilityConfigured(): boolean {
  return CAPABILITY_PATTERN.test(getRuntimeConfig().mcpCapability);
}

export function hasValidMcpCapability(value: string | undefined): boolean {
  const expected = getRuntimeConfig().mcpCapability;
  if (!value || !CAPABILITY_PATTERN.test(expected) || !CAPABILITY_PATTERN.test(value)) {
    return false;
  }
  return timingSafeEqual(digest(value), digest(expected));
}

export function capabilityFromMcpUrl(url: string): string | undefined {
  const pathname = new URL(url).pathname;
  const match = /^\/mcp\/([A-Za-z0-9_-]{43})$/.exec(pathname);
  return match?.[1];
}

export function notFoundResponse(): Response {
  return new Response(null, {
    status: 404,
    headers: { "Cache-Control": "no-store" },
  });
}
