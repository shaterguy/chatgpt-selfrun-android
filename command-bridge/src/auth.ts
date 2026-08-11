import { createRemoteJWKSet, jwtVerify, type JWTPayload } from "jose";
import type { AuthInfo, CallToolResult } from "@modelcontextprotocol/server";

import { getRuntimeConfig } from "./config.js";

type JwksState = {
  url: string;
  keys: ReturnType<typeof createRemoteJWKSet>;
};

let jwksState: JwksState | undefined;

function scopesFromPayload(payload: JWTPayload): string[] {
  const scopes = new Set<string>();
  if (typeof payload.scope === "string") {
    for (const scope of payload.scope.split(/\s+/).filter(Boolean)) {
      scopes.add(scope);
    }
  }
  if (Array.isArray(payload.permissions)) {
    for (const permission of payload.permissions) {
      if (typeof permission === "string" && permission) scopes.add(permission);
    }
  }
  return [...scopes];
}

function resourceMatches(payload: JWTPayload, resourceUrl: string): boolean {
  const claim = payload.resource;
  if (claim === undefined) return true;
  if (typeof claim === "string") return claim === resourceUrl;
  return Array.isArray(claim) && claim.includes(resourceUrl);
}

function jwksFor(url: string): ReturnType<typeof createRemoteJWKSet> {
  if (jwksState?.url === url) return jwksState.keys;
  const keys = createRemoteJWKSet(new URL(url));
  jwksState = { url, keys };
  return keys;
}

export async function verifyAccessToken(
  _request: Request,
  bearerToken?: string,
): Promise<AuthInfo | undefined> {
  if (!bearerToken) return undefined;
  const config = getRuntimeConfig();
  if (
    !config.auth0Issuer ||
    !config.auth0Audience ||
    !config.auth0JwksUrl ||
    config.auth0AllowedSubjects.length === 0
  ) {
    return undefined;
  }

  try {
    const { payload } = await jwtVerify(
      bearerToken,
      jwksFor(config.auth0JwksUrl),
      {
        issuer: config.auth0Issuer + "/",
        audience: config.auth0Audience,
        algorithms: ["RS256"],
      },
    );
    const subject = typeof payload.sub === "string" ? payload.sub : "";
    const expiresAt = payload.exp;
    if (
      !subject ||
      expiresAt === undefined ||
      expiresAt <= Math.floor(Date.now() / 1000) ||
      !config.auth0AllowedSubjects.includes(subject) ||
      !resourceMatches(payload, config.mcpResourceUrl)
    ) {
      return undefined;
    }
    return {
      token: bearerToken,
      clientId:
        typeof payload.azp === "string" && payload.azp
          ? payload.azp
          : subject,
      scopes: scopesFromPayload(payload),
      expiresAt,
      resource: new URL(config.mcpResourceUrl),
      extra: { sub: subject },
    };
  } catch {
    return undefined;
  }
}

export function oauthChallenge(
  message: string,
  error: "invalid_token" | "insufficient_scope" = "insufficient_scope",
): CallToolResult {
  const metadataUrl =
    getRuntimeConfig().mcpResourceUrl +
    "/.well-known/oauth-protected-resource";
  return {
    content: [{ type: "text", text: message }],
    isError: true,
    _meta: {
      "mcp/www_authenticate": [
        'Bearer resource_metadata="' +
          metadataUrl +
          '", error="' +
          error +
          '", error_description="' +
          message +
          '"',
      ],
    },
  };
}
