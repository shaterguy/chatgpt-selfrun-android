CREATE TABLE IF NOT EXISTS selfrun_commands (
    id BIGSERIAL PRIMARY KEY,
    command TEXT NOT NULL,
    saved_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    hash TEXT NOT NULL CHECK (hash ~ '^[0-9a-f]{64}$')
);

CREATE INDEX IF NOT EXISTS selfrun_commands_latest_idx
    ON selfrun_commands (id DESC);
