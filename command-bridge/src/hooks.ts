import { defineHook } from "workflow";
import type { PushAck } from "./contracts.js";

export interface DriveSignal {
  channelId: string;
  resourceState: string;
  messageNumber: string;
  resourceId: string;
}

export const driveHook = defineHook<DriveSignal>();
export const ackHook = defineHook<PushAck>();
