import { GoogleAuth } from "google-auth-library";
import type { IncomingMessage, ServerResponse } from "node:http";
import { normalizeFirebasePrivateKey } from "../src/firebase.js";
import { errorResponse, json, method } from "../src/http.js";

const FIREBASE_READ_SCOPE = "https://www.googleapis.com/auth/firebase.readonly";
const FORMAL_PACKAGE = "com.shaterguy.chatgptselfrun.drive";
const TEST_PACKAGE = "com.shaterguy.chatgptselfrun.drive.test";

type FirebaseAndroidApp = {
  name?: unknown;
  appId?: unknown;
  displayName?: unknown;
  projectId?: unknown;
  packageName?: unknown;
  state?: unknown;
};

type FirebaseAndroidAppsResponse = {
  apps?: unknown;
};

function publicApp(app: FirebaseAndroidApp) {
  return {
    appId: typeof app.appId === "string" ? app.appId : "",
    packageName: typeof app.packageName === "string" ? app.packageName : "",
    displayName: typeof app.displayName === "string" ? app.displayName : "",
    state: typeof app.state === "string" ? app.state : "",
  };
}

export default async function handler(request: IncomingMessage, response: ServerResponse): Promise<void> {
  try {
    method(request, "GET");
    const projectId = process.env.FIREBASE_PROJECT_ID?.trim() ?? "";
    const clientEmail = process.env.FIREBASE_CLIENT_EMAIL?.trim() ?? "";
    const rawPrivateKey = process.env.FIREBASE_PRIVATE_KEY?.trim() ?? "";
    if (!projectId || !clientEmail || !rawPrivateKey) {
      json(response, 503, { ok: false, error: "firebase server configuration missing" });
      return;
    }

    const privateKey = normalizeFirebasePrivateKey(rawPrivateKey);
    const auth = new GoogleAuth({
      credentials: { client_email: clientEmail, private_key: privateKey },
      scopes: [FIREBASE_READ_SCOPE],
    });
    const client = await auth.getClient();
    const access = await client.getAccessToken();
    if (!access.token) throw new Error("Firebase management access token unavailable");

    const management = await fetch(
      `https://firebase.googleapis.com/v1beta1/projects/${encodeURIComponent(projectId)}/androidApps?pageSize=100`,
      {
        method: "GET",
        headers: {
          authorization: `Bearer ${access.token}`,
          accept: "application/json",
          "cache-control": "no-store",
        },
      },
    );
    const text = await management.text();
    if (!management.ok) {
      json(response, management.status, {
        ok: false,
        error: "firebase management list failed",
        managementStatus: management.status,
      });
      return;
    }

    const parsed = JSON.parse(text) as FirebaseAndroidAppsResponse;
    const rawApps = Array.isArray(parsed.apps) ? parsed.apps : [];
    const apps = rawApps
      .filter((value): value is FirebaseAndroidApp => value !== null && typeof value === "object")
      .map(publicApp)
      .filter(app => app.appId || app.packageName);

    json(response, 200, {
      ok: true,
      projectId,
      formalPackage: FORMAL_PACKAGE,
      testPackage: TEST_PACKAGE,
      formalRegistered: apps.some(app => app.packageName === FORMAL_PACKAGE),
      testRegistered: apps.some(app => app.packageName === TEST_PACKAGE),
      apps,
    });
  } catch (error) {
    errorResponse(response, error);
  }
}
