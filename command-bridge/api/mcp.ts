import type { IncomingMessage, ServerResponse } from "node:http";

import { handler as mcpHandler } from "../src/mcp.js";
import { toWebRequest, writeWebResponse } from "../src/vercel-adapter.js";

export const config = {
  api: {
    bodyParser: false,
  },
};

export default async function handler(
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  const webRequest = await toWebRequest(request);
  const webResponse = await mcpHandler(webRequest);
  await writeWebResponse(response, webResponse);
}
