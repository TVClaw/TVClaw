import { createReadStream, existsSync } from "node:fs";
import { createServer, type Server } from "node:http";
import path from "node:path";
import { fileURLToPath } from "node:url";
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

function resolveClientApkPath(): string | undefined {
  const fromEnv = process.env.TVCLAW_CLIENT_APK?.trim();
  if (fromEnv && existsSync(fromEnv)) {
    return fromEnv;
  }
  const here = path.dirname(fileURLToPath(import.meta.url));
  const defaultPath = path.join(
    here,
    "..",
    "..",
    "client-android",
    "app",
    "build",
    "outputs",
    "apk",
    "debug",
    "app-debug.apk",
  );
  if (existsSync(defaultPath)) {
    return defaultPath;
  }
  return undefined;
}

const clientApkPath = resolveClientApkPath();

const httpServer: Server = createServer((req, res) => {
  const pathname = (req.url ?? "/").split("?")[0] ?? "/";

  if (req.method === "GET" && pathname === "/health") {
    res.writeHead(200);
    res.end("ok");
    return;
  }

  if (req.method === "GET" && pathname === "/") {
    if (!clientApkPath) {
      res.writeHead(503, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("client apk not built (assembleDebug) or TVCLAW_CLIENT_APK unset");
      return;
    }
    res.writeHead(200, { "Content-Type": "text/html; charset=utf-8" });
    res.end(
      "<!DOCTYPE html><meta charset=utf-8><title>TVClaw</title>" +
        "<p><a href=\"/tvclaw-client.apk\">Download TV client (APK)</a></p>",
    );
    return;
  }

  if (req.method === "GET" && pathname === "/tvclaw-client.apk") {
    if (!clientApkPath) {
      res.writeHead(404, { "Content-Type": "text/plain; charset=utf-8" });
      res.end("apk not available");
      return;
    }
    res.writeHead(200, {
      "Content-Type": "application/vnd.android.package-archive",
      "Content-Disposition": 'attachment; filename="tvclaw-client.apk"',
    });
    createReadStream(clientApkPath).pipe(res).on("error", () => {
      if (!res.headersSent) {
        res.writeHead(500);
      }
      res.end();
    });
    return;
  }

  if (req.method === "POST" && pathname === "/tv") {
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
    return;
  }

  res.writeHead(404);
  res.end();
});
httpServer.listen(httpPort);

process.stdout.write(`tvclaw brain ws ${port} http ${httpPort}\n`);
if (clientApkPath) {
  process.stdout.write(
    `tvclaw client apk http://<this-machine-lan-ip>:${httpPort}/tvclaw-client.apk\n`,
  );
} else {
  process.stdout.write(
    "tvclaw client apk not served (build apps/client-android or set TVCLAW_CLIENT_APK)\n",
  );
}

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
