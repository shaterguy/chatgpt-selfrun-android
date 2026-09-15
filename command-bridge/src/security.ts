import { randomBytes, timingSafeEqual } from "node:crypto";

const PREFIX = /^[A-Z]{2,8}$/;

export function createCapability(prefix: string): string {
  if (!PREFIX.test(prefix)) throw new Error("invalid capability prefix");
  return `${prefix}-${randomBytes(32).toString("base64url")}`;
}

export function safeEqual(left: string, right: string): boolean {
  const a = Buffer.from(left ?? "", "utf8");
  const b = Buffer.from(right ?? "", "utf8");
  if (a.length !== b.length) return false;
  return timingSafeEqual(a, b);
}
