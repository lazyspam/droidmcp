// DroidMCP relay: gives each phone a stable public MCP URL.
//
//   phone  --(outbound WebSocket, AGENT_KEY)-->  relay  <--(HTTPS)--  MCP client
//
// Public URL for a phone named "pixel":  https://<relay-host>/d/pixel/mcp
// The relay never sees tool logic. The phone checks the client's bearer token itself.
//
// Phone auth (same protocol as the Cloudflare relay):
//   - Each phone sends its own device secret (Authorization: Bearer <secret>). The first phone to use a
//     device ID claims it; later connections must present the same secret. Claims persist in CLAIMS_FILE.
//   - Optional AGENT_KEY makes the relay private: phones must also send X-Agent-Key.

import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import { WebSocketServer, WebSocket } from "ws";

const PORT = Number(process.env.PORT || 8080);
const AGENT_KEY = process.env.AGENT_KEY || "";
const CLAIMS_FILE = process.env.CLAIMS_FILE || "claims.json";
const TIMEOUT_MS = Number(process.env.REQUEST_TIMEOUT_MS || 610_000); // > the phone's 600s max shell timeout
const MAX_BODY = 50 * 1024 * 1024;
const MAX_PENDING_PER_DEVICE = Number(process.env.MAX_PENDING_PER_DEVICE || 32);
const FORWARD_HEADERS = ["authorization", "content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id"];
const RETURN_HEADERS = /^(content-type|mcp-session-id|www-authenticate|allow|retry-after)$/i;

if (AGENT_KEY && AGENT_KEY.length < 16) {
  console.error("error: AGENT_KEY must be 16+ characters (or unset it for an open relay).");
  process.exit(1);
}

/** device ID -> sha256(device secret), first come first served. */
let claims = {};
try {
  claims = JSON.parse(fs.readFileSync(CLAIMS_FILE, "utf8"));
} catch (e) {
  if (e.code !== "ENOENT") console.error(`warning: could not read ${CLAIMS_FILE}: ${e.message}; starting with no claims`);
}
function saveClaims() {
  try {
    fs.writeFileSync(CLAIMS_FILE + ".tmp", JSON.stringify(claims));
    fs.renameSync(CLAIMS_FILE + ".tmp", CLAIMS_FILE);
  } catch (e) {
    console.error(`warning: could not save ${CLAIMS_FILE}: ${e.message}`);
  }
}
const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");

const log = (...a) => console.log(new Date().toISOString(), ...a);
const agents = new Map(); // device -> ws
const pending = new Map(); // request id -> { resolve, device }

function safeEqual(a, b) {
  const x = Buffer.from(a || ""), y = Buffer.from(b || "");
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** JSON-RPC id from the client's request, so error replies can be proper JSON-RPC errors the client will display. */
function rpcId(body) {
  try {
    const m = JSON.parse(body);
    return m && !Array.isArray(m) && "id" in m ? m.id : null;
  } catch {
    return null;
  }
}

/** Error reply readable by both humans and MCP clients. */
function fail(res, status, message, body = "", extraHeaders = {}) {
  if (res.headersSent || res.writableEnded) return;
  const payload = { jsonrpc: "2.0", id: rpcId(body), error: { code: -32000, message } };
  res.writeHead(status, { "Content-Type": "application/json", ...extraHeaders });
  res.end(JSON.stringify(payload));
}

const server = http.createServer((req, res) => {
  let url;
  try {
    url = new URL(req.url, "http://x");
  } catch {
    return fail(res, 400, "Bad request URL.");
  }
  if (url.pathname === "/" || url.pathname === "/health") {
    res.writeHead(200, { "Content-Type": "application/json" });
    return res.end(JSON.stringify({ ok: true, devices: [...agents.keys()].length, pending: pending.size }));
  }
  const m = url.pathname.match(/^\/d\/([A-Za-z0-9_-]{1,64})(\/.*)?$/);
  if (!m) return fail(res, 404, "Not found. MCP endpoints look like /d/<device>/mcp.");
  const [, device, rest = "/"] = m;
  if (rest === "/status") {
    res.writeHead(200, { "Content-Type": "application/json" });
    return res.end(JSON.stringify({ device, connected: agents.get(device)?.readyState === WebSocket.OPEN }));
  }

  const chunks = [];
  let size = 0;
  let aborted = false;
  req.on("error", () => (aborted = true));
  req.on("data", (c) => {
    if (aborted) return;
    size += c.length;
    if (size > MAX_BODY) {
      aborted = true;
      fail(res, 413, `Request body too large (max ${MAX_BODY / 1024 / 1024} MB).`);
      req.destroy();
      return;
    }
    chunks.push(c);
  });
  req.on("end", () => {
    if (aborted || res.writableEnded) return;
    const body = Buffer.concat(chunks).toString("utf8");

    const ws = agents.get(device);
    if (!ws || ws.readyState !== WebSocket.OPEN) {
      return fail(res, 502, `Phone '${device}' is offline: it is not connected to the relay. ` +
        "Check the DroidMCP app is running and shows 'Relay: connected'.", body);
    }
    const inFlight = [...pending.values()].filter((p) => p.device === device).length;
    if (inFlight >= MAX_PENDING_PER_DEVICE) {
      return fail(res, 429, `Too many requests in flight for '${device}' (${inFlight}). Retry shortly.`, body, { "Retry-After": "2" });
    }

    const id = crypto.randomUUID();
    let done = false;
    const finish = (status, headers, replyBody) => {
      if (done) return;
      done = true;
      clearTimeout(timer);
      pending.delete(id);
      if (res.writableEnded || res.destroyed) return;
      try {
        const safe = { "Content-Type": "application/json" };
        for (const [k, v] of Object.entries(headers || {})) if (RETURN_HEADERS.test(k) && typeof v === "string") safe[k] = v;
        res.writeHead(Number.isInteger(status) && status >= 100 && status < 600 ? status : 502, safe);
        res.end(typeof replyBody === "string" ? replyBody : "");
      } catch (e) {
        log(`[!] bad reply from ${device}: ${e.message}`);
        if (!res.headersSent) fail(res, 502, "The phone sent a malformed reply.", body);
        else res.destroy();
      }
    };
    const timer = setTimeout(() => {
      finish(504, {}, JSON.stringify({ jsonrpc: "2.0", id: rpcId(body),
        error: { code: -32001, message: `Phone '${device}' did not answer within ${Math.round(TIMEOUT_MS / 1000)}s.` } }));
    }, TIMEOUT_MS);
    pending.set(id, {
      device,
      resolve: (reply) => finish(reply.status ?? 200, reply.headers, reply.body),
      failWith: (status, msg) => finish(status, {}, JSON.stringify({ jsonrpc: "2.0", id: rpcId(body), error: { code: -32000, message: msg } })),
    });
    // Client gave up (closed the connection): stop tracking it.
    res.on("close", () => {
      if (!done) { done = true; clearTimeout(timer); pending.delete(id); }
    });

    const headers = {};
    for (const h of FORWARD_HEADERS) if (req.headers[h]) headers[h] = String(req.headers[h]);
    ws.send(JSON.stringify({ id, method: req.method, path: rest + url.search, headers, body }), (err) => {
      if (err) pending.get(id)?.failWith(502, `Could not reach phone '${device}': ${err.message}`);
    });
  });
});

server.on("clientError", (err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_BODY });

function reject(socket, status, text) {
  if (socket.writable) socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

server.on("upgrade", (req, socket, head) => {
  socket.on("error", () => {});
  let url;
  try { url = new URL(req.url, "http://x"); } catch { return reject(socket, 400, "Bad Request"); }
  if (url.pathname !== "/agent") return reject(socket, 404, "Not Found");
  const device = url.searchParams.get("device") || "";
  if (!/^[A-Za-z0-9_-]{1,64}$/.test(device)) return reject(socket, 400, "Bad Request");
  const secret = (req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  if (AGENT_KEY && !safeEqual(req.headers["x-agent-key"] || "", AGENT_KEY)) {
    log(`[x] rejected '${device}' from ${req.socket.remoteAddress}: wrong or missing agent key`);
    return reject(socket, 401, "Unauthorized");
  }
  if (secret.length < 32) return reject(socket, 401, "Unauthorized");
  const hash = sha256(secret);
  if (claims[device] && !safeEqual(claims[device], hash)) {
    log(`[x] rejected '${device}': claimed by another phone`);
    return reject(socket, 403, "Forbidden");
  }
  if (!claims[device]) { claims[device] = hash; saveClaims(); }

  wss.handleUpgrade(req, socket, head, (ws) => {
    const old = agents.get(device);
    if (old) {
      log(`[~] ${device}: new connection replaces the old one`);
      old.close(4000, "replaced by a new connection");
    }
    agents.set(device, ws);
    log(`[+] ${device} connected from ${req.socket.remoteAddress}`);

    ws.isAlive = true;
    ws.on("pong", () => (ws.isAlive = true));
    ws.on("error", (e) => log(`[!] ${device} socket error: ${e.message}`));
    ws.on("message", (data) => {
      let msg;
      try {
        msg = JSON.parse(data.toString());
      } catch {
        return log(`[!] ${device} sent a non-JSON frame; ignored`);
      }
      const p = msg && typeof msg.id === "string" ? pending.get(msg.id) : undefined;
      if (!p) return; // late reply for a request that already timed out or was cancelled
      if (p.device !== device) return log(`[!] ${device} answered a request meant for ${p.device}; ignored`);
      p.resolve(msg);
    });
    ws.on("close", (code) => {
      const current = agents.get(device) === ws;
      if (current) agents.delete(device);
      log(`[-] ${device} disconnected (${code})`);
      if (!current) return; // a replacement connection now owns the in-flight requests
      for (const p of [...pending.values()]) {
        if (p.device === device) p.failWith(502, `Phone '${device}' disconnected before answering.`);
      }
    });
  });
});

// Drop dead phone connections (e.g. phone lost network without closing the socket).
const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
}, 30_000);

server.on("error", (e) => {
  if (e.code === "EADDRINUSE") console.error(`error: port ${PORT} is already in use. Set PORT to another port.`);
  else if (e.code === "EACCES") console.error(`error: no permission to listen on port ${PORT}. Use a port above 1024.`);
  else console.error("server error:", e);
  process.exit(1);
});

// Never let one bad request take the relay down.
process.on("uncaughtException", (e) => log("[!] uncaught exception:", e.stack || e));
process.on("unhandledRejection", (e) => log("[!] unhandled rejection:", e));

function shutdown(sig) {
  log(`${sig} received, shutting down`);
  clearInterval(heartbeat);
  for (const p of [...pending.values()]) p.failWith(503, "Relay is restarting. Retry in a moment.");
  for (const ws of wss.clients) ws.close(1001, "relay shutting down");
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

server.listen(PORT, () => log(`DroidMCP relay listening on :${PORT} (${AGENT_KEY ? "private: X-Agent-Key required" : "open registration"})`));
