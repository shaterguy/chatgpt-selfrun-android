import type { IncomingMessage, ServerResponse } from "node:http";

import { readyResponse } from "../src/ready.js";
import { writeWebResponse } from "../src/vercel-adapter.js";

function authorizationHeader(request: IncomingMessage): string | undefined {
  const value = request.headers.authorization;
  return Array.isArray(value) ? value[0] : value;
}

export default async function handler(
  request: IncomingMessage,
  response: ServerResponse,
): Promise<void> {
  if ((request.method ?? "GET").toUpperCase() !== "GET") {
    response.statusCode = 405;
    response.setHeader("Allow", "GET");
    response.end();
    return;
  }
  await writeWebResponse(
    response,
    await readyResponse(authorizationHeader(request)),
  );
}
