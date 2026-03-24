export type ProtocolAction =
  | "LAUNCH_APP"
  | "OPEN_URL"
  | "SEARCH"
  | "UNIVERSAL_SEARCH"
  | "MEDIA_CONTROL"
  | "KEY_EVENT"
  | "SLEEP_TIMER"
  | "VISION_SYNC"
  | "SHOW_TOAST";

export type MediaControl =
  | "PLAY"
  | "PAUSE"
  | "REWIND_30"
  | "FAST_FORWARD_30"
  | "MUTE"
  | "HOME"
  | "BACK";

export type TvKeyCode =
  | "DPAD_UP"
  | "DPAD_DOWN"
  | "DPAD_LEFT"
  | "DPAD_RIGHT"
  | "DPAD_CENTER"
  | "ENTER"
  | "BACK"
  | "HOME"
  | "MENU"
  | "CHANNEL_UP"
  | "CHANNEL_DOWN"
  | "VOLUME_UP"
  | "VOLUME_DOWN";

export interface ProtocolParams {
  app_id?: string;
  url?: string;
  query?: string;
  control?: MediaControl;
  keycode?: TvKeyCode;
  value?: string | number;
  message?: string;
  request_id?: string;
}

export interface VisionSyncResponse {
  type: "vision_sync_response";
  request_id: string;
  jpeg_base64?: string;
  error?: string;
}

export interface ProtocolPayload {
  action: ProtocolAction;
  params: ProtocolParams;
}

export interface TVClawEnvelope {
  request_id: string;
  timestamp: string;
  payload: ProtocolPayload;
}

export function createTVClawEnvelope(payload: ProtocolPayload): TVClawEnvelope {
  return {
    request_id: crypto.randomUUID(),
    timestamp: new Date().toISOString(),
    payload,
  };
}

export function parseTVClawEnvelope(raw: unknown): TVClawEnvelope {
  if (raw === null || typeof raw !== "object") {
    throw new TypeError("envelope must be an object");
  }
  const o = raw as Record<string, unknown>;
  if (typeof o.request_id !== "string" || o.request_id.length === 0) {
    throw new TypeError("request_id must be a non-empty string");
  }
  if (typeof o.timestamp !== "string" || o.timestamp.length === 0) {
    throw new TypeError("timestamp must be a non-empty string");
  }
  if (o.payload === null || typeof o.payload !== "object") {
    throw new TypeError("payload must be an object");
  }
  const p = o.payload as Record<string, unknown>;
  if (typeof p.action !== "string") {
    throw new TypeError("payload.action must be a string");
  }
  if (p.params !== undefined && (p.params === null || typeof p.params !== "object")) {
    throw new TypeError("payload.params must be an object when present");
  }
  return {
    request_id: o.request_id,
    timestamp: o.timestamp,
    payload: {
      action: p.action as ProtocolAction,
      params: (p.params ?? {}) as ProtocolParams,
    },
  };
}
