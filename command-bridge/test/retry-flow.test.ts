import { describe, expect, it } from "vitest";
import { AckDeliveryRetryState } from "../src/retry.js";

describe("ack delivery retry flow", () => {
  it("retries the same event after a lost first push and stops on PROCESSED", () => {
    const retry = new AckDeliveryRetryState();
    const eventId = "EV-deterministic-first-loss";
    const sentEventIds: string[] = [];

    if (retry.shouldSendBeforeWait()) sentEventIds.push(eventId);
    retry.onTimeout();
    if (retry.shouldSendBeforeWait()) sentEventIds.push(eventId);
    retry.onMatchingAck("PROCESSED");

    expect(sentEventIds).toEqual([eventId, eventId]);
    expect(retry.deliveryProcessed()).toBe(true);
    expect(retry.shouldSendBeforeWait()).toBe(false);
  });

  it("waits after RECEIVED and only resends after the next durable timeout", () => {
    const retry = new AckDeliveryRetryState();

    expect(retry.shouldSendBeforeWait()).toBe(true);
    retry.onMatchingAck("RECEIVED");
    expect(retry.deliveryProcessed()).toBe(false);
    expect(retry.shouldSendBeforeWait()).toBe(false);

    retry.onTimeout();
    expect(retry.shouldSendBeforeWait()).toBe(true);
  });
});
