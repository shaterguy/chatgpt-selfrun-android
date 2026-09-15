const ACK_RETRY_SECONDS = [15, 30, 60, 120, 300, 600] as const;

export function retryDelaySeconds(attempt: number): number {
  if (!Number.isInteger(attempt) || attempt < 0) {
    throw new RangeError("attempt must be a non-negative integer");
  }
  return ACK_RETRY_SECONDS[Math.min(attempt, ACK_RETRY_SECONDS.length - 1)];
}

export const ACK_RETRY_SCHEDULE_SECONDS = ACK_RETRY_SECONDS;
