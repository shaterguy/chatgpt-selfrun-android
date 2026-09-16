const TERMINAL_ERROR_NAMES = new Set(["HookNotFoundError", "RunExpiredError"]);
const MISSING_HISTORICAL_KEY = 'Invalid response from Vercel API, missing "key" field';
const HISTORICAL_KEY_GONE = /^Failed to fetch run key for .+ \(deployment .+\): HTTP (404|410)$/;

export function isTerminalAckResumeError(error: unknown): boolean {
  const name = error instanceof Error ? error.name : "";
  if (TERMINAL_ERROR_NAMES.has(name)) return true;

  const message = error instanceof Error ? error.message : "";
  if (message === MISSING_HISTORICAL_KEY) return true;
  return HISTORICAL_KEY_GONE.test(message);
}
