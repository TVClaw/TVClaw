import type { ProtocolPayload } from "@tvclaw/protocol";

export function visionSyncCommand(): ProtocolPayload {
  return { action: "VISION_SYNC", params: {} };
}
