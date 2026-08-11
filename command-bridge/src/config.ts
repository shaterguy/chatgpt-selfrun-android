export const DEFAULT_MCP_RESOURCE_URL =
  "https://selfrun-command-bridge.vercel.app";

function trimTrailingSlash(value: string): string {
  return value.replace(/\/+$/, "");
}

export interface RuntimeConfig {
  androidReadToken: string;
  mcpResourceUrl: string;
  auth0Issuer: string;
  auth0Audience: string;
  auth0AllowedSubjects: string[];
  auth0JwksUrl: string;
}

export function getRuntimeConfig(): RuntimeConfig {
  const auth0Issuer = trimTrailingSlash(
    (process.env.AUTH0_ISSUER ?? "").trim(),
  );
  const mcpResourceUrl = trimTrailingSlash(
    (process.env.MCP_RESOURCE_URL ?? DEFAULT_MCP_RESOURCE_URL).trim(),
  );
  const auth0AllowedSubjects = (process.env.AUTH0_ALLOWED_SUB ?? "")
    .split(",")
    .map((value) => value.trim())
    .filter(Boolean);

  return {
    androidReadToken: process.env.SELF_RUN_ANDROID_READ_TOKEN ?? "",
    mcpResourceUrl,
    auth0Issuer,
    auth0Audience: (process.env.AUTH0_AUDIENCE ?? "").trim(),
    auth0AllowedSubjects: [...new Set(auth0AllowedSubjects)],
    auth0JwksUrl:
      (process.env.AUTH0_JWKS_URL ?? "").trim() ||
      (auth0Issuer ? auth0Issuer + "/.well-known/jwks.json" : ""),
  };
}
