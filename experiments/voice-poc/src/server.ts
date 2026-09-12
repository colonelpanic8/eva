import { randomBytes, randomInt, timingSafeEqual } from "node:crypto";
import { readFile } from "node:fs/promises";
import { createServer } from "node:http";
import { fileURLToPath } from "node:url";
import { WebSocket, WebSocketServer } from "ws";
import { record, safeError } from "./rpc.ts";
import { Session } from "./session.ts";

const port = Number(process.env.EVA_PORT ?? randomInt(40000, 60000));
if (!Number.isInteger(port) || port < 1024 || port > 65535) throw new Error("Invalid EVA_PORT");
const token = randomBytes(24).toString("hex");
const server = createServer(async (req, res) => {
  const path = req.url?.split("?")[0];
  const file = path === "/" ? "index.html" : path === "/app.js" ? "app.js" : undefined;
  if (!file) {
    res.writeHead(404).end();
    return;
  }
  try {
    res.writeHead(200, {
      "Content-Type": file.endsWith("js") ? "text/javascript" : "text/html",
      "Cache-Control": "no-store",
      "Referrer-Policy": "no-referrer",
      "X-Content-Type-Options": "nosniff",
      "Content-Security-Policy":
        "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'",
    });
    res.end(await readFile(fileURLToPath(new URL(`../public/${file}`, import.meta.url))));
  } catch {
    res.writeHead(500).end("Run npm run build first");
  }
});
const wss = new WebSocketServer({ noServer: true, maxPayload: 128 * 1024 });
server.on("upgrade", (req, socket, head) => {
  const origin = req.headers.origin;
  let validOrigin = false;
  try {
    validOrigin = !!origin && new URL(origin).host === req.headers.host;
  } catch {}
  if (!validOrigin || req.url !== "/control") {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => wss.emit("connection", ws));
});
let owner: WebSocket | undefined;
const sessions = new Set<Session>();
wss.on("connection", (ws) => {
  let authorized = false;
  let session: Session | undefined;
  let commands = 0;
  let alive = true;
  const authTimer = setTimeout(() => ws.close(1008, "Authentication required"), 5000);
  const heartbeat = setInterval(() => {
    if (!alive) {
      ws.terminate();
      return;
    }
    alive = false;
    ws.ping();
  }, 10_000);
  ws.on("pong", () => {
    alive = true;
  });
  ws.on("message", (raw) => {
    void (async () => {
      const msg = record(JSON.parse(raw.toString()));
      if (!authorized) {
        const supplied = Buffer.from(typeof msg.token === "string" ? msg.token : "");
        if (supplied.length !== token.length || !timingSafeEqual(supplied, Buffer.from(token)))
          throw new Error("Invalid broker access token");
        if (owner) throw new Error("Another browser owns this experiment");
        authorized = true;
        owner = ws;
        clearTimeout(authTimer);
        ws.send(JSON.stringify({ kind: "authorized" }));
        return;
      }
      if (++commands > 200) throw new Error("Control-message limit reached");
      if (msg.type === "start") {
        if (session) throw new Error("Reconnect with a fresh control connection");
        if (typeof msg.sdp !== "string" || !msg.sdp.startsWith("v=0") || msg.sdp.length > 100000)
          throw new Error("Invalid WebRTC offer");
        if (typeof msg.instructions !== "string" || msg.instructions.length > 4000)
          throw new Error("Instructions must be at most 4000 characters");
        session = new Session((event) => {
          if (ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(event));
          if (event.kind === "closed") ws.close(1000, "Session ended");
        });
        sessions.add(session);
        await session.start(msg.sdp, msg.instructions);
      } else if (msg.type === "text") {
        if (typeof msg.text !== "string" || !msg.text.trim() || msg.text.length > 4000)
          throw new Error("Text must be 1–4000 characters");
        if (!session) throw new Error("Start a session first");
        if (msg.route !== undefined && msg.route !== "backend" && msg.route !== "realtime-context")
          throw new Error("Unknown text route");
        await session.text(msg.text, msg.route as "backend" | "realtime-context" | undefined);
      } else if (msg.type === "stop") await session?.close();
      else throw new Error("Unknown control command");
    })().catch((error) => {
      if (ws.readyState === WebSocket.OPEN)
        ws.send(JSON.stringify({ kind: "error", message: safeError(error) }));
      ws.close(1008, "Request rejected");
    });
  });
  ws.on("error", () => ws.terminate());
  ws.on("close", () => {
    clearTimeout(authTimer);
    clearInterval(heartbeat);
    if (owner === ws) owner = undefined;
    void (async () => {
      await session?.close();
      if (session) sessions.delete(session);
    })();
  });
});
server.listen(port, "0.0.0.0", () => {
  // The fragment is a local broker capability, never an OpenAI credential.
  console.log(`EVA voice POC: http://localhost:${port}/#${token}`);
  console.log(
    "Bound 0.0.0.0; this HTTP UI requires localhost for microphone access. Ctrl-C stops only this harness.",
  );
});
server.on("error", (error) => {
  console.error(safeError(error));
  process.exitCode = 1;
});
for (const signal of ["SIGINT", "SIGTERM"] as const)
  process.on(signal, () => {
    void Promise.all([...sessions].map((session) => session.close())).finally(() => {
      for (const ws of wss.clients) ws.terminate();
      wss.close();
      server.close();
    });
  });
