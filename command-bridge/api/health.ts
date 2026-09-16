import type { IncomingMessage, ServerResponse } from "node:http";
import { firebaseConfigurationStatus } from "../src/firebase.js";
import { json } from "../src/http.js";

export default function handler(_request: IncomingMessage, response: ServerResponse): void {
  const firebase = firebaseConfigurationStatus();
  json(response, 200, {
    service: "selfrun-command-bridge",
    status: "ok",
    version: "3.2.5-dev4-wdk2",
    firebaseConfigured: firebase.configured,
    firebaseConfigReason: firebase.reason,
  });
}
