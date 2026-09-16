import { build } from "esbuild";
import { mkdir, readFile, writeFile } from "node:fs/promises";
import { join } from "node:path";
import { fileURLToPath } from "node:url";

const root = fileURLToPath(new URL("../", import.meta.url));
const output = join(root, ".vercel", "output");
const workflowId = "workflow//./workflows/watch//watchWorkflow";
const manifestPath = join(output, "functions", ".well-known", "workflow", "v1", "manifest.json");
const manifest = JSON.parse(await readFile(manifestPath, "utf8"));
if (!JSON.stringify(manifest).includes(workflowId)) {
  throw new Error(`Workflow manifest does not contain ${workflowId}`);
}

const routes = [
  ["api/health.ts", "api/health.func"],
  ["api/watch/register.ts", "api/watch/register.func"],
  ["api/push/ack.ts", "api/push/ack.func"],
  ["api/drive/webhook.ts", "api/drive/webhook.func"],
];

for (const [source, relativeOut] of routes) {
  const functionDir = join(output, "functions", relativeOut);
  await mkdir(functionDir, { recursive: true });
  await build({
    entryPoints: [join(root, source)],
    outfile: join(functionDir, "index.cjs"),
    bundle: true,
    platform: "node",
    target: "node24",
    format: "cjs",
    sourcemap: true,
    logLevel: "info",
    footer: { js: "module.exports = module.exports.default ?? module.exports;" },
  });
  await writeFile(
    join(functionDir, ".vc-config.json"),
    JSON.stringify({
      runtime: "nodejs24.x",
      handler: "index.cjs",
      launcherType: "Nodejs",
      shouldAddHelpers: true,
      shouldAddSourcemapSupport: true,
      maxDuration: 30,
    }, null, 2) + "\n",
    "utf8",
  );
}

console.log("SelfRun command bridge Vercel Build Output API routes created.");
