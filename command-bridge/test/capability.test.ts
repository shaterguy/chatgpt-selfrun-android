import { describe, expect, it } from "vitest";

import {
  capabilityFromMcpUrl,
  hasValidMcpCapability,
  isMcpCapabilityConfigured,
} from "../src/capability.js";

const CAPABILITY = "a".repeat(43);

describe("SelfRun MCP capability boundary", () => {
  it("accepts only an exact base64url path segment", () => {
    process.env.SELF_RUN_MCP_CAPABILITY = CAPABILITY;
    expect(isMcpCapabilityConfigured()).toBe(true);
    expect(
      capabilityFromMcpUrl(
        "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY,
      ),
    ).toBe(CAPABILITY);
    expect(
      capabilityFromMcpUrl(
        "https://selfrun-command-bridge.vercel.app/mcp/" + CAPABILITY + "/x",
      ),
    ).toBeUndefined();
    expect(
      capabilityFromMcpUrl("https://selfrun-command-bridge.vercel.app/api/mcp"),
    ).toBeUndefined();
    expect(hasValidMcpCapability(CAPABILITY)).toBe(true);
    expect(hasValidMcpCapability("b".repeat(43))).toBe(false);
  });

  it("rejects malformed or absent configured capabilities", () => {
    process.env.SELF_RUN_MCP_CAPABILITY = "short";
    expect(isMcpCapabilityConfigured()).toBe(false);
    expect(hasValidMcpCapability("short")).toBe(false);
  });
});
