import { createServer, type Server } from "node:http";
import { WebSocketServer, WebSocket } from "ws";
import type { ProtocolPayload } from "@tvclaw/protocol";
import { sendToAllTvs } from "./tools/tv_control.js";

const port = Number(process.env.TVCLAW_BRAIN_PORT ?? process.env.PORT ?? 8765);
const httpPort = Number(process.env.TVCLAW_HTTP_PORT ?? 8770);
const wss = new WebSocketServer({ port });
const clients = new Set<WebSocket>();

wss.on("connection", (ws) => {
  clients.add(ws);
  ws.on("close", () => {
    clients.delete(ws);
  });
});

const httpServer: Server = createServer((req, res) => {
  if (req.method === "GET" && req.url === "/health") {
    res.writeHead(200);
    res.end("ok");
    return;
  }
  if (req.method !== "POST" || req.url !== "/tv") {
    res.writeHead(404);
    res.end();
    return;
  }
  const chunks: Buffer[] = [];
  req.on("data", (c) => {
    chunks.push(c as Buffer);
  });
  req.on("end", () => {
    try {
      const raw = JSON.parse(Buffer.concat(chunks).toString("utf8")) as unknown;
      const payload = parsePocPayload(raw);
      sendToAllTvs(clients, payload);
      res.writeHead(200, { "Content-Type": "application/json" });
      res.end(JSON.stringify({ ok: true, tvs: clients.size }));
    } catch {
      res.writeHead(400);
      res.end();
    }
  });
});
httpServer.listen(httpPort);

process.stdout.write(`tvclaw brain ws ${port} http ${httpPort}\n`);

function parsePocPayload(raw: unknown): ProtocolPayload {
  if (raw === null || typeof raw !== "object") {
    throw new TypeError();
  }
  const o = raw as Record<string, unknown>;
  const inner = o.payload !== undefined ? o.payload : o;
  if (inner === null || typeof inner !== "object") {
    throw new TypeError();
  }
  const p = inner as Record<string, unknown>;
  if (typeof p.action !== "string") {
    throw new TypeError();
  }
  const params =
    p.params !== undefined && p.params !== null && typeof p.params === "object"
      ? (p.params as ProtocolPayload["params"])
      : {};
  return { action: p.action as ProtocolPayload["action"], params };
}
