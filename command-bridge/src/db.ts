import { neon } from "@neondatabase/serverless";

import { getRuntimeConfig } from "./config.js";
import {
  recordFromRow,
  type CommandRecord,
  validateCommand,
} from "./command.js";

function sqlClient() {
  const { databaseUrl } = getRuntimeConfig();
  if (!databaseUrl) {
    throw new Error("DATABASE_URL is not configured");
  }
  return neon(databaseUrl);
}

export async function insertCommand(
  command: string,
  hash: string,
  savedAt?: Date,
): Promise<CommandRecord> {
  validateCommand(command);
  const sql = sqlClient();
  const rows = await sql.query(
    "INSERT INTO selfrun_commands (command, saved_at, hash) VALUES ($1, $2, $3) RETURNING id, command, saved_at, hash",
    [command, savedAt ?? new Date(), hash],
  );
  return recordFromRow(rows[0] as Record<string, unknown>);
}

export async function latestCommand(): Promise<CommandRecord | null> {
  const sql = sqlClient();
  const rows = await sql.query(
    "SELECT id, command, saved_at, hash FROM selfrun_commands ORDER BY id DESC LIMIT 1",
  );
  return rows.length
    ? recordFromRow(rows[0] as Record<string, unknown>)
    : null;
}

export async function probeDatabase(): Promise<void> {
  const sql = sqlClient();
  await sql.query("SELECT 1");
}
