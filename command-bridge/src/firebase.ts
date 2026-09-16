import { GoogleAuth } from "google-auth-library";
import type { PushEnvelope } from "./contracts.js";

const FIREBASE_SCOPE = "https://www.googleapis.com/auth/firebase.messaging";

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

function extractPemBlock(value: string): string | null {
  const match = value.match(
    /-----BEGIN (?:RSA )?PRIVATE KEY-----[\s\S]*?-----END (?:RSA )?PRIVATE KEY-----/,
  );
  return match?.[0]?.trim() ?? null;
}

export function normalizeFirebasePrivateKey(raw: string): string {
  let value = raw.trim();
  if (!value) return "";

  const jsonValue = extractJsonPrivateKey(value);
  if (jsonValue !== null) value = jsonValue;
  value = normalizeNewlines(value);
  const pem = extractPemBlock(value);
  if (pem !== null) return pem;

  try {
    const decoded = Buffer.from(value, "base64").toString("utf8").trim();
    if (decoded) {
      const decodedJsonValue = extractJsonPrivateKey(decoded);
      value = normalizeNewlines(decodedJsonValue ?? decoded);
      const decodedPem = extractPemBlock(value);
      if (decodedPem !== null) return decodedPem;
    }
  } catch {
    // Invalid base64 is left for Google Auth to reject without logging the secret.
  }

  return value;
}

export function firebaseConfigured(): boolean {
  return [
    process.env.FIREBASE_PROJECT_ID,
    process.env.FIREBASE_CLIENT_EMAIL,
    process.env.FIREBASE_PRIVATE_KEY,
  ].every(value => typeof value === "string" && value.trim().length > 0);
}

export interface FcmSendResult {
  sent: boolean;
  messageName?: string;
  status?: number;
}

export async function sendFcmStep(fcmToken: string, push: PushEnvelope): Promise<FcmSendResult> {
  "use step";
  if (!firebaseConfigured()) return { sent: false, status: 503 };

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
