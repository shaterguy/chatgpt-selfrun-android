import { BlobNotFoundError, get, head, put } from "@vercel/blob";

import {
  commandHash,
  type LatestCommandRecord,
  validateCommand,
} from "./command.js";

export const LATEST_COMMAND_PATHNAME = "selfrun/latest-command.json";

function validateRecord(value: unknown): LatestCommandRecord {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    throw new Error("latest command blob is invalid");
  }
  const record = value as Record<string, unknown>;
  if (
    typeof record.command !== "string" ||
    typeof record.saved_at !== "string" ||
    typeof record.hash !== "string"
  ) {
    throw new Error("latest command blob is invalid");
  }
  validateCommand(record.command);
  if (commandHash(record.command) !== record.hash) {
    throw new Error("latest command blob hash mismatch");
  }
  return {
    command: record.command,
    savedAt: record.saved_at,
    hash: record.hash,
  };
}

export async function saveLatestCommand(
  command: string,
  savedAt = new Date().toISOString(),
): Promise<LatestCommandRecord> {
  validateCommand(command);
  const record = {
    command,
    saved_at: savedAt,
    hash: commandHash(command),
  };
  await put(LATEST_COMMAND_PATHNAME, JSON.stringify(record), {
    access: "private",
    addRandomSuffix: false,
    allowOverwrite: true,
    contentType: "application/json; charset=utf-8",
  });
  return {
    command: record.command,
    savedAt: record.saved_at,
    hash: record.hash,
  };
}

export async function latestCommand(): Promise<LatestCommandRecord | null> {
  const result = await get(LATEST_COMMAND_PATHNAME, {
    access: "private",
    useCache: false,
  });
  if (!result) return null;
  if (result.statusCode !== 200 || !result.stream) {
    throw new Error("latest command blob could not be read");
  }
  return validateRecord(await new Response(result.stream).json());
}

export async function probeBlob(): Promise<"ok" | "empty"> {
  try {
    await head(LATEST_COMMAND_PATHNAME);
    return "ok";
  } catch (error) {
    if (error instanceof BlobNotFoundError) return "empty";
    throw error;
  }
}
