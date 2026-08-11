import type { IncomingMessage, ServerResponse } from "node:http";

import { healthResponse } from "../src/health.js";
import { writeWebResponse } from "../src/vercel-adapter.js";

export default async function handler(
  _request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  await writeWebResponse(response, await healthResponse());
}
