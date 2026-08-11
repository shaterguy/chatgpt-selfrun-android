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

export interface LatestCommandRecord {
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
