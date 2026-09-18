import { sleep } from "workflow";
import type { PushAck, WatchRegistration } from "../src/contracts.js";
import { buildPushEnvelope, ackMatchesIdentity, normalizeDriveResourceState } from "../src/delivery-policy.js";
import { sendFcmStep } from "../src/firebase.js";
import { ackHook, driveHook } from "../src/hooks.js";
import { AckDeliveryRetryState, retryDelaySeconds } from "../src/retry.js";
import { createCapability } from "../src/security.js";

export interface WatchWorkflowInput extends WatchRegistration {
  watchKey: string;
  channelId: string;
  expirationMs: number;
}

interface ActiveDelivery {
  eventId: string;
  push: ReturnType<typeof buildPushEnvelope>;
  ackIterator: AsyncIterator<PushAck>;
  pendingAck: Promise<IteratorResult<PushAck>>;
  retry: AckDeliveryRetryState;
  attempt: number;
}

async function createEventIdStep(): Promise<string> {
  "use step";
  return createCapability("EV");
}

export async function watchWorkflow(input: WatchWorkflowInput): Promise<{ status: string; eventId?: string }> {
  "use workflow";

  const identity = {
    installationId: input.installationId,
    applicationId: input.applicationId,
    taskId: input.taskId,
    turnId: input.turnId,
    resultDocumentId: input.resultDocumentId,
  };
  const driveEvents = driveHook.create({ token: input.watchKey });
  const driveIterator = driveEvents[Symbol.asyncIterator]();
  let pendingDrive = driveIterator.next();
  let lastMessageNumber = "";
  let active: ActiveDelivery | null = null;

  while (true) {
    if (active === null) {
      const next = await pendingDrive;
      if (next.done) return { status: "WATCH_CLOSED" };
      pendingDrive = driveIterator.next();
      const signal = next.value;
      if (signal.channelId !== input.channelId) continue;
      if (normalizeDriveResourceState(signal.resourceState) === "IGNORE") continue;
      if (signal.messageNumber === lastMessageNumber) continue;
      lastMessageNumber = signal.messageNumber;

      const eventId = await createEventIdStep();
      const push = buildPushEnvelope(eventId, identity);
      const ackEvents = ackHook.create({ token: eventId });
      const ackIterator = ackEvents[Symbol.asyncIterator]();
      active = {
        eventId,
        push,
        ackIterator,
        pendingAck: ackIterator.next(),
        retry: new AckDeliveryRetryState(),
        attempt: 0,
      };
      continue;
    }

    if (active.retry.shouldSendBeforeWait()) {
      await sendFcmStep(input.fcmToken, active.push);
    }
    const waitSeconds = retryDelaySeconds(active.attempt);
    const outcome = await Promise.race([
      pendingDrive.then(value => ({ kind: "drive" as const, value })),
      active.pendingAck.then(value => ({ kind: "ack" as const, value })),
      sleep(`${waitSeconds} seconds`).then(() => ({ kind: "timeout" as const })),
    ]);

    if (outcome.kind === "drive") {
      if (outcome.value.done) return { status: "WATCH_CLOSED", eventId: active.eventId };
      pendingDrive = driveIterator.next();
      const signal = outcome.value.value;
      if (signal.channelId !== input.channelId) continue;
      if (normalizeDriveResourceState(signal.resourceState) === "IGNORE") continue;
      if (signal.messageNumber === lastMessageNumber) continue;
      lastMessageNumber = signal.messageNumber;

      // A newer Drive change supersedes the previous transport delivery immediately.
      // Do not wait for the old PENDING event's ACK/retry clock before notifying Android.
      const eventId = await createEventIdStep();
      const push = buildPushEnvelope(eventId, identity);
      const ackEvents = ackHook.create({ token: eventId });
      const ackIterator = ackEvents[Symbol.asyncIterator]();
      active = {
        eventId,
        push,
        ackIterator,
        pendingAck: ackIterator.next(),
        retry: new AckDeliveryRetryState(),
        attempt: 0,
      };
      continue;
    }

    if (outcome.kind === "timeout") {
      active.retry.onTimeout();
      active.attempt += 1;
      if (active.attempt >= 150) active = null;
      continue;
    }

    if (outcome.value.done) {
      active.pendingAck = active.ackIterator.next();
      active.retry.waitWithoutResend();
      active.attempt += 1;
      if (active.attempt >= 150) active = null;
      continue;
    }

    const ack = outcome.value.value;
    active.pendingAck = active.ackIterator.next();
    if (!ackMatchesIdentity(ack, active.eventId, identity)) {
      active.retry.waitWithoutResend();
      active.attempt += 1;
      if (active.attempt >= 150) active = null;
      continue;
    }

    active.retry.onMatchingAck(ack.state);
    if (active.retry.deliveryProcessed()) {
      active = null;
      continue;
    }
    // RECEIVED proves device delivery, but not Result processing. Keep the logical
    // delivery active while still allowing newer Drive changes to preempt it.
    active.attempt += 1;
    if (active.attempt >= 150) active = null;
  }
}
