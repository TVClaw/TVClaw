import type { WebSocket } from "ws";
import type { ProtocolPayload } from "@tvclaw/protocol";
import { sendToAllTvs } from "./tools/tv_control.js";

export type TvCommandSink = (payload: ProtocolPayload) => void;

export function createTvCommandSink(clients: Set<WebSocket>): TvCommandSink {
  return (payload) => sendToAllTvs(clients, payload);
}
