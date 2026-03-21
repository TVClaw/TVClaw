import { WebSocket } from "ws";
import { createTVClawEnvelope, type ProtocolPayload } from "@tvclaw/protocol";

export function sendToAllTvs(clients: ReadonlySet<WebSocket>, payload: ProtocolPayload): void {
  const msg = JSON.stringify(createTVClawEnvelope(payload));
  for (const c of clients) {
    if (c.readyState === WebSocket.OPEN) {
      c.send(msg);
    }
  }
}
