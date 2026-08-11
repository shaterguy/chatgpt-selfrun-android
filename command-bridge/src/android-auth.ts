import { createHash, timingSafeEqual } from "node:crypto";

import { getRuntimeConfig } from "./config.js";

function digest(value: string): Buffer {
  return createHash("sha256").update(value, "utf8").digest();
}

export function hasValidAndroidReadToken(
  authorizationHeader: string | undefined,
): boolean {
  const expected = getRuntimeConfig().androidReadToken;
  if (!expected || !authorizationHeader) return false;
  const match = /^Bearer ([^\s]+)$/.exec(authorizationHeader);
  if (!match) return false;
  return timingSafeEqual(digest(match[1]), digest(expected));
}
