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
  return [...scopes];
}

function resourceMatches(payload: JWTPayload, resourceUrl: string): boolean {
  const claim = payload.resource;
  if (typeof claim === "string") return claim === resourceUrl;
  if (Array.isArray(claim) && claim.includes(resourceUrl)) return true;
  if (typeof payload.aud === "string") return payload.aud === resourceUrl;
  return Array.isArray(payload.aud) && payload.aud.includes(resourceUrl);
}

function jwksFor(url: string): ReturnType<typeof createRemoteJWKSet> {
  if (jwksState?.url === url) return jwksState.keys;
  const keys = createRemoteJWKSet(new URL(url));
  jwksState = { url, keys };
  return keys;
}

function logAuthDiagnostic(
  status: "unconfigured" | "rejected" | "accepted",
  reason: string,
  details: Record<string, unknown> = {},
): void {
  console.info(
    "mcp_auth",
    JSON.stringify({ status, reason, ...details }),
  );
}

function authFailureReason(error: unknown): string {
  if (!(error instanceof Error)) return "token_verification_failed";
  switch (error.name) {
    case "JWTExpired":
      return "expired";
    case "JWTClaimValidationFailed":
      return "claim_validation_failed";
    case "JWSSignatureVerificationFailed":
      return "signature_verification_failed";
    case "JWKSTimeout":
      return "jwks_timeout";
    default:
      return "token_verification_failed";
  }
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
    config.auth0AllowedSubjects.length === 0 ||
    config.auth0Audience !== config.mcpResourceUrl
  ) {
    logAuthDiagnostic("unconfigured", "missing_runtime_config", {
      issuerConfigured: Boolean(config.auth0Issuer),
      audienceConfigured: Boolean(config.auth0Audience),
      jwksConfigured: Boolean(config.auth0JwksUrl),
      allowedSubjectCount: config.auth0AllowedSubjects.length,
      audienceMatchesResource: config.auth0Audience === config.mcpResourceUrl,
    });
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
        requiredClaims: ["exp", "nbf", "sub"],
      },
    );
    const subject = typeof payload.sub === "string" ? payload.sub : "";
    const scopes = scopesFromPayload(payload);
    const claimChecks = {
      subjectPresent: Boolean(subject),
      allowedSubject: config.auth0AllowedSubjects.includes(subject),
      resourceMatch: resourceMatches(payload, config.mcpResourceUrl),
      scopeWritePresent: scopes.includes("commands:write"),
    };
    if (
      !claimChecks.subjectPresent ||
      !claimChecks.allowedSubject ||
      !claimChecks.resourceMatch
    ) {
      logAuthDiagnostic("rejected", "claim_validation_failed", claimChecks);
      return undefined;
    }

    logAuthDiagnostic("accepted", "jwt_verified", claimChecks);
    return {
      token: bearerToken,
      clientId:
        typeof payload.azp === "string" && payload.azp
          ? payload.azp
          : subject,
      scopes,
      expiresAt: payload.exp,
      resource: new URL(config.mcpResourceUrl),
      extra: { sub: subject },
    };
  } catch (error) {
    logAuthDiagnostic("rejected", authFailureReason(error));
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
        "Bearer resource_metadata=\"" +
          metadataUrl +
          "\", error=\"" +
          error +
          "\", error_description=\"" +
          message +
          "\"",
      ],
    },
  };
}
