import { createHash } from "node:crypto";

export const MAX_COMMAND_BYTES = 1024 * 1024;

export class CommandInputError extends Error {
  readonly code: "empty_command" | "command_too_large";

  constructor(
    code: "empty_command" | "command_too_large",
    message: string,
  ) {
    super(message);
    this.name = "CommandInputError";
    this.code = code;
  }
}

export interface CommandRecord {
  commandId: string;
  command: string;
  savedAt: string;
  hash: string;
}

export function validateCommand(command: unknown): asserts command is string {
  if (typeof command !== "string" || command.length === 0) {
    throw new CommandInputError(
      "empty_command",
      "command must be a non-empty string",
    );
  }
  if (Buffer.byteLength(command, "utf8") > MAX_COMMAND_BYTES) {
    throw new CommandInputError(
      "command_too_large",
      "command exceeds the 1 MiB UTF-8 limit",
    );
  }
}

export function commandHash(command: string): string {
  return createHash("sha256").update(command, "utf8").digest("hex");
}

export function toIsoString(value: unknown): string {
  if (value instanceof Date) return value.toISOString();
  const text = String(value ?? "");
  const parsed = new Date(text);
  return Number.isNaN(parsed.valueOf()) ? text : parsed.toISOString();
}

export function recordFromRow(row: Record<string, unknown>): CommandRecord {
  const command = String(row.command ?? "");
  return {
    commandId: String(row.id ?? ""),
    command,
    savedAt: toIsoString(row.saved_at),
    hash: String(row.hash ?? commandHash(command)),
  };
}
