import type { IncomingMessage, ServerResponse } from "node:http";
import { firebaseConfigured } from "../src/firebase.js";
import { json } from "../src/http.js";

export default function handler(_request: IncomingMessage, response: ServerResponse): void {
  json(response, 200, {
    service: "selfrun-command-bridge",
    status: "ok",
    version: "3.2.5-dev4",
    firebaseConfigured: firebaseConfigured(),
  });
}
