import { shouldTerminateDelivery } from "./delivery-policy.js";

const ACK_RETRY_SECONDS = [15, 30, 60, 120, 300, 600] as const;

export function retryDelaySeconds(attempt: number): number {
  if (!Number.isInteger(attempt) || attempt < 0) {
    throw new RangeError("attempt must be a non-negative integer");
  }
  return ACK_RETRY_SECONDS[Math.min(attempt, ACK_RETRY_SECONDS.length - 1)];
}

export class AckDeliveryRetryState {
  private sendBeforeWait = true;
  private processed = false;

  shouldSendBeforeWait(): boolean {
    return this.sendBeforeWait && !this.processed;
  }

  onTimeout(): void {
    if (!this.processed) this.sendBeforeWait = true;
  }

  waitWithoutResend(): void {
    this.sendBeforeWait = false;
  }

  onMatchingAck(state: string): void {
    this.sendBeforeWait = false;
    if (shouldTerminateDelivery(state)) this.processed = true;
  }

  deliveryProcessed(): boolean {
    return this.processed;
  }
}

export const ACK_RETRY_SCHEDULE_SECONDS = ACK_RETRY_SECONDS;
