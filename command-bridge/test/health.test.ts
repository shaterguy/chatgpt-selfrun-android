import { describe, expect, it } from "vitest";

import { healthResponse } from "../src/health.js";

describe("SelfRun liveness health contract", () => {
  it("returns a constant-cost response without configuration or Blob reads", async () => {
    process.env.SELF_RUN_ANDROID_READ_TOKEN = "read-token";
    process.env.AUTH0_ISSUER = "https://tenant.example.com/";
    process.env.AUTH0_AUDIENCE = "https://selfrun-command-bridge.vercel.app";
    process.env.AUTH0_ALLOWED_SUB = "auth0|allowed";
    const response = healthResponse();

    expect(response.status).toBe(200);
    await expect(response.json()).resolves.toEqual({
      service: "selfrun-command-bridge",
      status: "ok",
    });
  });
});
