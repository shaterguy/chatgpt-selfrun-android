import { describe, expect, it } from "vitest";

import {
  CommandInputError,
  MAX_COMMAND_BYTES,
  commandHash,
  validateCommand,
} from "../src/command.js";

describe("SelfRun command preservation", () => {
  it("keeps multiline Markdown, code fences, JSON, quotes, and backslashes unchanged", () => {
    const command = [
      "한글",
      "~~~json",
      '{"quote":"\\"","slash":"\\\\","emoji":"🧪"}',
      "~~~",
      "마지막 줄",
    ].join("\n");

    validateCommand(command);

    expect(command).toContain('\\"');
    expect(command).toContain("\\\\");
    expect(commandHash(command)).toHaveLength(64);
  });

  it("rejects only an empty string and preserves whitespace inside valid commands", () => {
    expect(() => validateCommand("")).toThrowError(CommandInputError);
    expect(() => validateCommand(" \n ")).not.toThrow();
  });

  it("rejects a command over the UTF-8 byte limit without truncating it", () => {
    const command = "가".repeat(Math.ceil(MAX_COMMAND_BYTES / 3));
    expect(() => validateCommand(command)).toThrowError(
      /1 MiB UTF-8 limit/,
    );
  });
});
