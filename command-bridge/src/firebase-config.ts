import { createPrivateKey } from "node:crypto";
import { firebaseConfigured, normalizeFirebasePrivateKey } from "./firebase.js";

export type FirebaseConfigReason = "ok" | "missing_env" | "invalid_private_key";

export interface FirebaseConfigurationStatus {
  configured: boolean;
  reason: FirebaseConfigReason;
}

export function firebaseConfigurationStatus(): FirebaseConfigurationStatus {
  if (!firebaseConfigured()) {
    return { configured: false, reason: "missing_env" };
  }

  const privateKey = normalizeFirebasePrivateKey(process.env.FIREBASE_PRIVATE_KEY!);
  try {
    createPrivateKey(privateKey);
    return { configured: true, reason: "ok" };
  } catch {
    return { configured: false, reason: "invalid_private_key" };
  }
}
