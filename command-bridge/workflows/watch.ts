import { sleep } from "workflow";
import type { WatchRegistration } from "../src/contracts.js";
import { buildPushEnvelope, ackMatchesIdentity, normalizeDriveResourceState, shouldTerminateDelivery } from "../src/delivery-policy.js";
import { sendFcmStep } from "../src/firebase.js";
import { ackHook, driveHook } from "../src/hooks.js";
import { retryDelaySeconds } from "../src/retry.js";
import { createCapability } from "../src/security.js";

export interface WatchWorkflowInput extends WatchRegistration {
  watchKey: string;
  channelId: string;
  expirationMs: number;
}

async function createEventIdStep(): Promise<string> {
  "use step";
  return createCapability("EV");
}

export async function watchWorkflow(input: WatchWorkflowInput): Promise<{ status: string; eventId?: string }> {
  "use workflow";

  const driveEvents = driveHook.create({ token: input.watchKey });
  for await (const signal of driveEvents) {
    if (signal.channelId !== input.channelId) continue;
    if (normalizeDriveResourceState(signal.resourceState) === "IGNORE") continue;

    const eventId = await createEventIdStep();
    const identity = {
      installationId: input.installationId,
      applicationId: input.applicationId,
      taskId: input.taskId,
      turnId: input.turnId,
      resultDocumentId: input.resultDocumentId,
    };
    const push = buildPushEnvelope(eventId, identity);
    const ackEvents = ackHook.create({ token: eventId });
    const ackIterator = ackEvents[Symbol.asyncIterator]();
    let pendingAck = ackIterator.next();
    let sendBeforeWait = true;
    let deliveryProcessed = false;

    for (let attempt = 0; attempt < 150; attempt += 1) {
      if (sendBeforeWait) await sendFcmStep(input.fcmToken, push);
      const waitSeconds = retryDelaySeconds(attempt);
      const outcome = await Promise.race([
        pendingAck.then(value => ({ kind: "ack" as const, value })),
        sleep(`${waitSeconds} seconds`).then(() => ({ kind: "timeout" as const })),
      ]);

      if (outcome.kind === "timeout") {
        sendBeforeWait = true;
        continue;
      }

      if (outcome.value.done) {
        pendingAck = ackIterator.next();
        sendBeforeWait = false;
        continue;
      }

      const ack = outcome.value.value;
      pendingAck = ackIterator.next();
      if (!ackMatchesIdentity(ack, eventId, identity)) {
        sendBeforeWait = false;
        continue;
      }
      if (shouldTerminateDelivery(ack.state)) {
        deliveryProcessed = true;
        break;
      }
      // RECEIVED proves device delivery, but not Result processing. Wait again and
      // only resend when the durable timeout expires.
      sendBeforeWait = false;
    }
    if (!deliveryProcessed) {
      return { status: "ACK_TIMEOUT", eventId };
    }
  }
  return { status: "WATCH_CLOSED" };
}
