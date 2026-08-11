import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { exportJWK, generateKeyPair, SignJWT } from "jose";

const blobMocks = vi.hoisted(() => ({
  get: vi.fn(),
  head: vi.fn(),
  put: vi.fn(),
}));

vi.mock("@vercel/blob", () => blobMocks);

import { handler } from "../src/mcp.js";

const ISSUER = "https://tenant.example.com";
const RESOURCE = "https://selfrun-command-bridge.vercel.app";
const JWKS_URL = ISSUER + "/.well-known/jwks.json";

let keyPair: Awaited<ReturnType<typeof generateKeyPair>>;
let publicJwk: Record<string, unknown>;

beforeAll(async () => {
  keyPair = await generateKeyPair("RS256");
  publicJwk = await exportJWK(keyPair.publicKey);
  vi.stubGlobal(
    "fetch",
    vi.fn(async () =>
      new Response(JSON.stringify({ keys: [{ ...publicJwk, kid: "test-key" }] }), {
        status: 200,
        headers: { "content-type": "application/json" },
      }),
    ),
  );
});

afterAll(() => {
  vi.unstubAllGlobals();
});

beforeEach(() => {
  process.env.MCP_RESOURCE_URL = RESOURCE;
  process.env.AUTH0_ISSUER = ISSUER;
  process.env.AUTH0_AUDIENCE = RESOURCE;
  process.env.AUTH0_JWKS_URL = JWKS_URL;
  process.env.AUTH0_ALLOWED_SUB = "auth0|allowed";
  blobMocks.put.mockReset();
  blobMocks.put.mockResolvedValue({ url: "blob://test" });
});

afterEach(() => {
  delete process.env.MCP_RESOURCE_URL;
  delete process.env.AUTH0_ISSUER;
  delete process.env.AUTH0_AUDIENCE;
  delete process.env.AUTH0_JWKS_URL;
  delete process.env.AUTH0_ALLOWED_SUB;
});

async function signedToken(
  claims: Record<string, unknown>,
): Promise<string> {
  return new SignJWT(claims)
    .setProtectedHeader({ alg: "RS256", kid: "test-key" })
    .setIssuer(ISSUER + "/")
    .setAudience(RESOURCE)
    .setSubject("auth0|allowed")
    .setIssuedAt()
    .setNotBefore(Math.floor(Date.now() / 1000) - 1)
    .setExpirationTime(Math.floor(Date.now() / 1000) + 3600)
    .sign(keyPair.privateKey);
}

function request(token: string, command: string): Request {
  return new Request(RESOURCE + "/mcp", {
    method: "POST",
    headers: {
      Accept: "application/json, text/event-stream",
      "Content-Type": "application/json",
      Authorization: "Bearer " + token,
    },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/call",
      params: {
        name: "save_selfrun_command",
        arguments: { command },
      },
    }),
  });
}

describe("SelfRun signed-token authorization boundary", () => {
  it("rejects a valid token with permissions but no OAuth write scope", async () => {
    const token = await signedToken({ permissions: ["commands:write"] });
    const response = await handler(request(token, "must not be stored"));

    expect(response.status).toBe(403);
    expect(response.headers.get("www-authenticate")).toContain(
      "commands:write",
    );
    expect(blobMocks.put).not.toHaveBeenCalled();
  });

  it("accepts a valid token carrying commands:write in scope", async () => {
    const token = await signedToken({ scope: "commands:write" });
    const response = await handler(request(token, "authorized command"));

    expect(response.status).toBe(200);
    const payload = await response.json();
    expect(payload.result.isError).not.toBe(true);
    expect(blobMocks.put).toHaveBeenCalledTimes(1);
    expect(blobMocks.put.mock.calls[0]?.[1]).toContain("authorized command");
  });
});
