export const REGISTER_SCHEMA = "selfrun-watch-register-v1" as const;
export const ACK_SCHEMA = "selfrun-push-ack-v1" as const;
export const PUSH_SCHEMA = "selfrun-push-v1" as const;
export const PUSH_TYPE = "RESULT_CHANGED" as const;

export type AckState = "RECEIVED" | "PROCESSED";

export interface WatchRegistration {
  schema: typeof REGISTER_SCHEMA;
  installationId: string;
  applicationId: string;
  taskId: string;
  turnId: string;
  resultDocumentId: string;
  fcmToken: string;
}

export interface PushAck {
  schema: typeof ACK_SCHEMA;
  eventId: string;
  installationId: string;
  applicationId: string;
  taskId: string;
  turnId: string;
  resultDocumentId: string;
  state: AckState;
}

export interface PushEnvelope {
  schema: typeof PUSH_SCHEMA;
  type: typeof PUSH_TYPE;
  eventId: string;
  installationId: string;
  applicationId: string;
  taskId: string;
  turnId: string;
  resultDocumentId: string;
}

const REGISTER_KEYS = new Set([
  "schema", "installationId", "applicationId", "taskId", "turnId",
  "resultDocumentId", "fcmToken",
]);
const ACK_KEYS = new Set([
  "schema", "eventId", "installationId", "applicationId", "taskId", "turnId",
  "resultDocumentId", "state",
]);

export function parseRegistration(input: unknown): WatchRegistration {
  const object = strictObject(input, REGISTER_KEYS);
  const schema = exact(object, "schema", REGISTER_SCHEMA);
  return {
    schema,
    installationId: boundedString(object, "installationId", 8, 128),
    applicationId: boundedString(object, "applicationId", 8, 160),
    taskId: boundedString(object, "taskId", 8, 160),
    turnId: boundedString(object, "turnId", 8, 200),
    resultDocumentId: boundedString(object, "resultDocumentId", 8, 200),
    fcmToken: boundedString(object, "fcmToken", 16, 4096),
  };
}

export function parseAck(input: unknown): PushAck {
  const object = strictObject(input, ACK_KEYS);
  const schema = exact(object, "schema", ACK_SCHEMA);
  const state = boundedString(object, "state", 8, 16);
  if (state !== "RECEIVED" && state !== "PROCESSED") {
    throw new Error("invalid ACK state");
  }
  return {
    schema,
    eventId: boundedString(object, "eventId", 16, 160),
    installationId: boundedString(object, "installationId", 8, 128),
    applicationId: boundedString(object, "applicationId", 8, 160),
    taskId: boundedString(object, "taskId", 8, 160),
    turnId: boundedString(object, "turnId", 8, 200),
    resultDocumentId: boundedString(object, "resultDocumentId", 8, 200),
    state,
  };
}

function strictObject(input: unknown, allowed: Set<string>): Record<string, unknown> {
  if (typeof input !== "object" || input === null || Array.isArray(input)) {
    throw new Error("JSON object required");
  }
  const object = input as Record<string, unknown>;
  for (const key of Object.keys(object)) {
    if (!allowed.has(key)) throw new Error(`unexpected field: ${key}`);
  }
  return object;
}

function boundedString(object: Record<string, unknown>, key: string, min: number, max: number): string {
  const value = object[key];
  if (typeof value !== "string") throw new Error(`${key} must be a string`);
  if (value.length < min || value.length > max) throw new Error(`${key} length invalid`);
  if (value.trim() !== value || /[\0\r\n]/.test(value)) throw new Error(`${key} contains invalid characters`);
  return value;
}

function exact<T extends string>(object: Record<string, unknown>, key: string, expected: T): T {
  const value = boundedString(object, key, expected.length, expected.length);
  if (value !== expected) throw new Error(`${key} mismatch`);
  return expected;
}
