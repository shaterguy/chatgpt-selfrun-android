import { readFile } from "node:fs/promises";
import { describe, expect, it } from "vitest";

const root = new URL("../", import.meta.url);

async function source(path: string): Promise<string> {
  return readFile(new URL(path, root), "utf8");
}

describe("Vercel Workflow build contract", () => {
  it("builds Workflow SDK queue handlers before the ordinary API functions", async () => {
    const pkg = JSON.parse(await source("package.json")) as { scripts?: { build?: string } };
    expect(pkg.scripts?.build).toContain("workflow build --target vercel-build-output-api");
    expect(pkg.scripts?.build).toContain("scripts/build-vercel-api.mjs");
  });

  it("starts the exact workflow metadata generated from workflows/watch.ts", async () => {
    const register = await source("api/watch/register.ts");
    const workflow = await source("workflows/watch.ts");
    const builder = await source("scripts/build-vercel-api.mjs");
    const id = "workflow//./workflows/watch//watchWorkflow";
    expect(register).toContain(id);
    expect(builder).toContain(id);
    expect(workflow).toContain('"use workflow"');
    expect(workflow).toContain('"use step"');
  });
});
