import {
  PUSH_SCHEMA,
  PUSH_TYPE,
  type AckState,
  type PushAck,
  type PushEnvelope,
} from "./contracts.js";

export interface RoutingIdentity {
  installationId: string;
  applicationId: string;
  taskId: string;
  turnId: string;
  resultDocumentId: string;
}

export function shouldTerminateDelivery(state: AckState): boolean {
  return state === "PROCESSED";
}

export function ackMatchesIdentity(
  ack: PushAck,
  eventId: string,
  identity: RoutingIdentity,
): boolean {
  return ack.eventId === eventId
    && ack.installationId === identity.installationId
    && ack.applicationId === identity.applicationId
    && ack.taskId === identity.taskId
    && ack.turnId === identity.turnId
    && ack.resultDocumentId === identity.resultDocumentId;
}

export function normalizeDriveResourceState(value: string): "IGNORE" | "DELIVER" {
  return value.toLowerCase() === "sync" ? "IGNORE" : "DELIVER";
}

export function buildPushEnvelope(eventId: string, identity: RoutingIdentity): PushEnvelope {
  return {
    schema: PUSH_SCHEMA,
    type: PUSH_TYPE,
    eventId,
    installationId: identity.installationId,
    applicationId: identity.applicationId,
    taskId: identity.taskId,
    turnId: identity.turnId,
    resultDocumentId: identity.resultDocumentId,
  };
}
