import { GoogleAuth } from "google-auth-library";

const scope = "https://www.googleapis.com/auth/firebase.readonly";
const formalPackage = "com.shaterguy.chatgptselfrun.drive";
const testPackage = "com.shaterguy.chatgptselfrun.drive.test";

function normalizePrivateKey(raw) {
  let value = String(raw ?? "").trim();
  if (!value) return "";
  try {
    const parsed = JSON.parse(value);
    if (typeof parsed === "string") value = parsed.trim();
    else if (parsed && typeof parsed.private_key === "string") value = parsed.private_key.trim();
  } catch { }
  value = value.replace(/\\n/g, "\n").trim();
  if (value.includes("-----BEGIN") && value.includes("PRIVATE KEY-----")) return value;
  try {
    const decoded = Buffer.from(value, "base64").toString("utf8").trim();
    if (decoded.includes("-----BEGIN") && decoded.includes("PRIVATE KEY-----")) return decoded.replace(/\\n/g, "\n").trim();
  } catch { }
  return value;
}

const projectId = process.env.FIREBASE_PROJECT_ID?.trim() ?? "";
const clientEmail = process.env.FIREBASE_CLIENT_EMAIL?.trim() ?? "";
const privateKey = normalizePrivateKey(process.env.FIREBASE_PRIVATE_KEY);

if (!projectId || !clientEmail || !privateKey) {
  console.log("FIREBASE_ANDROID_APPS_DIAGNOSTIC " + JSON.stringify({ ok: false, reason: "missing_env" }));
  process.exit(2);
}

try {
  const auth = new GoogleAuth({
    credentials: { client_email: clientEmail, private_key: privateKey },
    scopes: [scope],
  });
  const client = await auth.getClient();
  const access = await client.getAccessToken();
  if (!access.token) throw new Error("access_token_unavailable");

  const response = await fetch(
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
  if (!response.ok) {
    console.log("FIREBASE_ANDROID_APPS_DIAGNOSTIC " + JSON.stringify({ ok: false, reason: "management_list_failed", status: response.status }));
    process.exit(3);
  }
  const body = await response.json();
  const apps = (Array.isArray(body.apps) ? body.apps : []).map(app => ({
    appId: typeof app?.appId === "string" ? app.appId : "",
    packageName: typeof app?.packageName === "string" ? app.packageName : "",
    displayName: typeof app?.displayName === "string" ? app.displayName : "",
    state: typeof app?.state === "string" ? app.state : "",
  })).filter(app => app.appId || app.packageName);
  console.log("FIREBASE_ANDROID_APPS_DIAGNOSTIC " + JSON.stringify({
    ok: true,
    projectId,
    formalRegistered: apps.some(app => app.packageName === formalPackage),
    testRegistered: apps.some(app => app.packageName === testPackage),
    apps,
  }));
} catch (error) {
  console.log("FIREBASE_ANDROID_APPS_DIAGNOSTIC " + JSON.stringify({
    ok: false,
    reason: error instanceof Error ? error.message.slice(0, 120) : "unknown_error",
  }));
  process.exit(4);
}
