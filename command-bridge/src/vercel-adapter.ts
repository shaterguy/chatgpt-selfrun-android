import type {
  IncomingHttpHeaders,
  IncomingMessage,
  ServerResponse,
} from "node:http";

import { publicRequestUrl, readRequestBody } from "./http.js";

function headerEntries(headers: IncomingHttpHeaders): Headers {
  const result = new Headers();
  for (const [name, value] of Object.entries(headers)) {
    if (Array.isArray(value)) {
      for (const item of value) result.append(name, item);
    } else if (value !== undefined) {
      result.set(name, value);
    }
  }
  return result;
}

export async function toWebRequest(
  request: IncomingMessage,
): Promise<Request> {
  const method = (request.method ?? "GET").toUpperCase();
  const body =
    method === "GET" || method === "HEAD"
      ? undefined
      : await readRequestBody(request);
  return new Request(publicRequestUrl(request), {
    method,
    headers: headerEntries(request.headers),
    body: body ? new Uint8Array(body) : undefined,
  });
}

export async function writeWebResponse(
  response: ServerResponse,
  webResponse: Response,
): Promise<void> {
  response.statusCode = webResponse.status;
  webResponse.headers.forEach((value, name) => {
    response.setHeader(name, value);
  });
  if (!webResponse.body) {
    response.end();
    return;
  }
  const reader = webResponse.body.getReader();
  try {
    while (true) {
      const next = await reader.read();
      if (next.done) break;
      response.write(Buffer.from(next.value));
    }
  } finally {
    reader.releaseLock();
  }
  response.end();
}
