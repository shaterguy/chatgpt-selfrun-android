import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import { exportJWK, generateKeyPair, SignJWT } from "jose";

import { verifyAccessToken } from "../src/auth.js";

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

describe("SelfRun OAuth scope derivation", () => {
  it("does not promote Auth0 permissions into OAuth scopes", async () => {
    const token = await signedToken({ permissions: ["commands:write"] });
    const authInfo = await verifyAccessToken(new Request(RESOURCE), token);

    expect(authInfo).toBeDefined();
    expect(authInfo?.scopes).toEqual([]);
    expect(authInfo?.scopes).not.toContain("commands:write");
  });

  it("accepts commands:write only from the OAuth scope claim", async () => {
    const token = await signedToken({ scope: "openid commands:write" });
    const authInfo = await verifyAccessToken(new Request(RESOURCE), token);

    expect(authInfo?.scopes).toContain("commands:write");
  });
});
