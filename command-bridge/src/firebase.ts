import { createPrivateKey } from "node:crypto";
import { GoogleAuth } from "google-auth-library";
import type { PushEnvelope } from "./contracts.js";

const FIREBASE_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

type FirebaseConfigReason = "ok" | "missing_env" | "invalid_private_key";

export interface FirebaseConfigurationStatus {
  configured: boolean;
  reason: FirebaseConfigReason;
}

function extractJsonPrivateKey(value: string): string | null {
  try {
    const parsed = JSON.parse(value) as unknown;
    if (typeof parsed === "string") return parsed.trim();
    if (
      parsed !== null &&
      typeof parsed === "object" &&
      "private_key" in parsed &&
      typeof (parsed as { private_key?: unknown }).private_key === "string"
    ) {
      return (parsed as { private_key: string }).private_key.trim();
    }
  } catch {
    // Not JSON. Continue with the original value.
  }
  return null;
}

function normalizeNewlines(value: string): string {
  return value.replace(/\\n/g, "\n").trim();
}

function looksLikePrivateKey(value: string): boolean {
  return /-----BEGIN (?:RSA )?PRIVATE KEY-----/.test(value);
}

export function normalizeFirebasePrivateKey(raw: string): string {
  let value = raw.trim();
  if (!value) return "";

  const jsonValue = extractJsonPrivateKey(value);
  if (jsonValue !== null) value = jsonValue;
  value = normalizeNewlines(value);
  if (looksLikePrivateKey(value)) return value;

  try {
    const decoded = Buffer.from(value, "base64").toString("utf8").trim();
    if (decoded) {
      const decodedJsonValue = extractJsonPrivateKey(decoded);
      value = normalizeNewlines(decodedJsonValue ?? decoded);
      if (looksLikePrivateKey(value)) return value;
    }
  } catch {
    // Invalid base64 is handled by createPrivateKey() below.
  }

  return value;
}

export function firebaseConfigurationStatus(): FirebaseConfigurationStatus {
  const projectId = process.env.FIREBASE_PROJECT_ID?.trim() ?? "";
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL?.trim() ?? "";
  const rawPrivateKey = process.env.FIREBASE_PRIVATE_KEY ?? "";
  if (!projectId || !clientEmail || !rawPrivateKey.trim()) {
    return { configured: false, reason: "missing_env" };
  }

  const privateKey = normalizeFirebasePrivateKey(rawPrivateKey);
  try {
    createPrivateKey(privateKey);
    return { configured: true, reason: "ok" };
  } catch {
    return { configured: false, reason: "invalid_private_key" };
  }
}

export function firebaseConfigured(): boolean {
  return firebaseConfigurationStatus().configured;
}

export interface FcmSendResult {
  sent: boolean;
  messageName?: string;
  status?: number;
}

export async function sendFcmStep(fcmToken: string, push: PushEnvelope): Promise<FcmSendResult> {
  "use step";
  const configuration = firebaseConfigurationStatus();
  if (!configuration.configured) return { sent: false, status: 503 };

  const projectId = process.env.FIREBASE_PROJECT_ID!.trim();
  const clientEmail = process.env.FIREBASE_CLIENT_EMAIL!.trim();
  const privateKey = normalizeFirebasePrivateKey(process.env.FIREBASE_PRIVATE_KEY!);
  const auth = new GoogleAuth({
    credentials: { client_email: clientEmail, private_key: privateKey },
    scopes: [FIREBASE_SCOPE],
  });
  const client = await auth.getClient();
  const access = await client.getAccessToken();
  if (!access.token) throw new Error("Firebase access token unavailable");

  const response = await fetch(
    `https://fcm.googleapis.com/v1/projects/${encodeURIComponent(projectId)}/messages:send`,
    {
      method: "POST",
      headers: {
        authorization: `Bearer ${access.token}`,
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
      },
      body: JSON.stringify({
        message: {
          token: fcmToken,
          data: push,
          android: {
            priority: "HIGH",
            ttl: "86400s",
            collapse_key: "selfrun-result",
          },
        },
      }),
    },
  );
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`FCM send failed (${response.status}): ${text.slice(0, 300)}`);
  }
  let messageName = "";
  try {
    const parsed = JSON.parse(text) as { name?: unknown };
    if (typeof parsed.name === "string") messageName = parsed.name;
  } catch { }
  return { sent: true, messageName, status: response.status };
}
