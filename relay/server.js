// DroidMCP relay: gives each phone a stable public MCP URL.
//
//   phone --(outbound WebSocket /agent?device=<id>)--> relay <--(HTTPS /d/<id>/mcp)-- MCP client
//
// The relay only forwards requests. The phone checks the client's bearer token itself.
//
// Phone auth: the phone sends its device secret as `Authorization: Bearer <secret>`. The first phone to use a
// device ID claims it; later connections must present the same secret. Claims are stored hashed in CLAIMS_FILE
// and dropped after 90 days without a connection. Set AGENT_KEY to make the relay private (phones must
// then also send `X-Agent-Key`).

import http from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import { WebSocketServer, WebSocket } from "ws";

const PORT = Number(process.env.PORT || 8080);
const AGENT_KEY = process.env.AGENT_KEY || "";
const CLAIMS_FILE = process.env.CLAIMS_FILE || "claims.json";

const TIMEOUT_MS = 610_000; // longer than the phone's 600s max shell timeout
const MAX_BODY = 50 * 1024 * 1024;
const MAX_DEVICES = 1000; // phones connected at once
const MAX_CLAIMS = 10_000; // device IDs remembered
const CLAIM_TTL_MS = 90 * 86_400_000; // forget a device ID after 90 days without a connection
const MAX_PENDING_PER_DEVICE = 32;
const MAX_PENDING = 2000; // across all phones
const RATE_HTTP = 300; // MCP requests per client IP per minute
const RATE_AGENT = 20; // phone connection attempts per IP per minute

const DEVICE_RE = /^[A-Za-z0-9_-]{1,64}$/;
const FORWARD_HEADERS = ["authorization", "content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id"];
const RETURN_HEADERS = /^(content-type|mcp-session-id|www-authenticate|allow|retry-after)$/i;
const BASE_HEADERS = { "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store" };

if (AGENT_KEY && AGENT_KEY.length < 16) {
  console.error("error: AGENT_KEY must be 16+ characters (or unset it for an open relay).");
  process.exit(1);
}

const log = (...a) => console.log(new Date().toISOString(), ...a);
const sha256 = (s) => crypto.createHash("sha256").update(s).digest("hex");

function safeEqual(a, b) {
  const x = Buffer.from(a || "");
  const y = Buffer.from(b || "");
  return x.length === y.length && crypto.timingSafeEqual(x, y);
}

/** Client IP. Behind a proxy (Railway, Caddy) it is the first X-Forwarded-For entry. */
function clientIp(req) {
  const fwd = req.headers["x-forwarded-for"];
  return (fwd ? String(fwd).split(",")[0].trim() : req.socket.remoteAddress) || "?";
}

// --- device claims: id -> { hash, seen } ---

let claims = {};
try {
  const raw = JSON.parse(fs.readFileSync(CLAIMS_FILE, "utf8"));
  for (const [id, v] of Object.entries(raw)) {
    claims[id] = typeof v === "string" ? { hash: v, seen: Date.now() } : v; // old files stored only the hash
  }
} catch (e) {
  if (e.code !== "ENOENT") console.error(`warning: could not read ${CLAIMS_FILE}: ${e.message}; starting empty`);
}

let saveTimer = null;
function saveClaims() {
  if (saveTimer) return;
  saveTimer = setTimeout(() => {
    saveTimer = null;
    try {
      fs.writeFileSync(CLAIMS_FILE + ".tmp", JSON.stringify(claims), { mode: 0o600 });
      fs.renameSync(CLAIMS_FILE + ".tmp", CLAIMS_FILE);
    } catch (e) {
      console.error(`warning: could not save ${CLAIMS_FILE}: ${e.message}`);
    }
  }, 1000);
}

function pruneClaims() {
  const cutoff = Date.now() - CLAIM_TTL_MS;
  let removed = 0;
  for (const [id, c] of Object.entries(claims)) {
    if (c.seen < cutoff && !agents.has(id)) { delete claims[id]; removed++; }
  }
  if (removed) { log(`pruned ${removed} stale claims`); saveClaims(); }
}

// --- rate limiting: fixed one-minute windows per key ---

const hits = new Map();
function limited(key, max) {
  const now = Date.now();
  const h = hits.get(key);
  if (!h || now - h.start > 60_000) { hits.set(key, { start: now, n: 1 }); return false; }
  return ++h.n > max;
}

// --- state ---

const agents = new Map(); // device -> ws
const pending = new Map(); // request id -> { device, finish }
const pendingByDevice = new Map(); // device -> count

function track(device, delta) {
  const n = (pendingByDevice.get(device) || 0) + delta;
  if (n > 0) pendingByDevice.set(device, n); else pendingByDevice.delete(device);
}

// --- HTTP side: MCP clients ---

/** JSON-RPC id of the client's request, so errors show up properly in MCP clients. */
function rpcId(body) {
  try {
    const m = JSON.parse(body);
    return m && !Array.isArray(m) && "id" in m ? m.id : null;
  } catch {
    return null;
  }
}

function sendJson(res, status, payload, headers = {}) {
  if (res.headersSent || res.writableEnded) return;
  res.writeHead(status, { ...BASE_HEADERS, "Content-Type": "application/json", ...headers });
  res.end(JSON.stringify(payload));
}

function fail(res, status, message, body = "", headers = {}) {
  sendJson(res, status, { jsonrpc: "2.0", id: rpcId(body), error: { code: -32000, message } }, headers);
}

function readBody(req, res, done) {
  const chunks = [];
  let size = 0;
  req.on("data", (c) => {
    size += c.length;
    if (size > MAX_BODY) {
      fail(res, 413, `Request body too large (max ${MAX_BODY / 1024 / 1024} MB).`);
      req.destroy();
    } else chunks.push(c);
  });
  req.on("end", () => { if (!res.writableEnded) done(Buffer.concat(chunks).toString("utf8")); });
  req.on("error", () => {});
}

function forward(req, res, device, path, body) {
  const ws = agents.get(device);
  if (!ws || ws.readyState !== WebSocket.OPEN) {
    return fail(res, 502, `Phone '${device}' is offline. Check the DroidMCP app is running and shows 'Relay: connected'.`, body);
  }
  if ((pendingByDevice.get(device) || 0) >= MAX_PENDING_PER_DEVICE || pending.size >= MAX_PENDING) {
    return fail(res, 429, `Too many requests in flight for '${device}'. Retry shortly.`, body, { "Retry-After": "2" });
  }

  const id = crypto.randomUUID();
  let done = false;
  const finish = (status, headers, replyBody) => {
    if (done) return;
    done = true;
    clearTimeout(timer);
    pending.delete(id);
    track(device, -1);
    if (res.writableEnded || res.destroyed) return;
    const out = { ...BASE_HEADERS, "Content-Type": "application/json" };
    for (const [k, v] of Object.entries(headers || {})) if (RETURN_HEADERS.test(k) && typeof v === "string") out[k] = v;
    try {
      res.writeHead(Number.isInteger(status) && status >= 100 && status < 600 ? status : 502, out);
      res.end(typeof replyBody === "string" ? replyBody : "");
    } catch {
      res.destroy(); // header values from the phone were invalid
    }
  };
  const failWith = (status, message) =>
    finish(status, {}, JSON.stringify({ jsonrpc: "2.0", id: rpcId(body), error: { code: -32000, message } }));

  const timer = setTimeout(() => failWith(504, `Phone '${device}' did not answer within ${Math.round(TIMEOUT_MS / 1000)}s.`), TIMEOUT_MS);
  pending.set(id, { device, finish, failWith });
  track(device, 1);
  res.on("close", () => { if (!done) finish(499, {}, ""); }); // client gave up

  const headers = {};
  for (const h of FORWARD_HEADERS) if (req.headers[h]) headers[h] = String(req.headers[h]);
  ws.send(JSON.stringify({ id, method: req.method, path, headers, body }), (err) => {
    if (err) failWith(502, `Could not reach phone '${device}': ${err.message}`);
  });
}

const server = http.createServer((req, res) => {
  let url;
  try { url = new URL(req.url, "http://relay"); } catch { return fail(res, 400, "Bad request URL."); }

  if (url.pathname === "/" || url.pathname === "/health") return sendJson(res, 200, { ok: true });

  const m = url.pathname.match(/^\/d\/([^/]+)(\/.*)?$/);
  if (!m || !DEVICE_RE.test(m[1])) return fail(res, 404, "Not found. MCP endpoints look like /d/<device>/mcp.");
  const [, device, rest = "/"] = m;

  if (limited(`http:${clientIp(req)}`, RATE_HTTP)) return fail(res, 429, "Too many requests. Slow down.", "", { "Retry-After": "10" });

  if (rest === "/status") return sendJson(res, 200, { device, connected: agents.get(device)?.readyState === WebSocket.OPEN });

  readBody(req, res, (body) => forward(req, res, device, rest + url.search, body));
});

server.headersTimeout = 30_000;
server.requestTimeout = 0; // long tool calls are bounded by TIMEOUT_MS instead
server.on("clientError", (_err, socket) => {
  if (socket.writable) socket.end("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
});

// --- WebSocket side: phones ---

const wss = new WebSocketServer({ noServer: true, maxPayload: MAX_BODY });

function reject(socket, status, text) {
  if (socket.writable) socket.write(`HTTP/1.1 ${status} ${text}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`);
  socket.destroy();
}

server.on("upgrade", (req, socket, head) => {
  socket.on("error", () => {});
  const ip = clientIp(req);
  let url;
  try { url = new URL(req.url, "http://relay"); } catch { return reject(socket, 400, "Bad Request"); }
  if (url.pathname !== "/agent") return reject(socket, 404, "Not Found");
  if (limited(`agent:${ip}`, RATE_AGENT)) return reject(socket, 429, "Too Many Requests");

  const device = url.searchParams.get("device") || "";
  if (!DEVICE_RE.test(device)) return reject(socket, 400, "Bad Request");
  if (AGENT_KEY && !safeEqual(String(req.headers["x-agent-key"] || ""), AGENT_KEY)) {
    log(`[x] ${device} from ${ip}: wrong or missing agent key`);
    return reject(socket, 401, "Unauthorized");
  }
  const secret = String(req.headers.authorization || "").replace(/^Bearer\s+/i, "");
  if (secret.length < 32) return reject(socket, 401, "Unauthorized");

  const hash = sha256(secret);
  const claim = claims[device];
  if (claim && !safeEqual(claim.hash, hash)) {
    log(`[x] ${device} from ${ip}: claimed by another phone`);
    return reject(socket, 403, "Forbidden");
  }
  if (!claim && Object.keys(claims).length >= MAX_CLAIMS) pruneClaims();
  if (!claim && Object.keys(claims).length >= MAX_CLAIMS) return reject(socket, 503, "Service Unavailable");
  if (!agents.has(device) && agents.size >= MAX_DEVICES) return reject(socket, 503, "Service Unavailable");

  claims[device] = { hash, seen: Date.now() };
  saveClaims();

  wss.handleUpgrade(req, socket, head, (ws) => {
    const old = agents.get(device);
    if (old) old.close(4000, "replaced by a new connection");
    agents.set(device, ws);
    log(`[+] ${device} connected from ${ip}`);

    ws.isAlive = true;
    ws.on("pong", () => (ws.isAlive = true));
    ws.on("error", (e) => log(`[!] ${device}: ${e.message}`));
    ws.on("message", (data) => {
      let msg;
      try { msg = JSON.parse(data.toString()); } catch { return; }
      const p = msg && typeof msg.id === "string" ? pending.get(msg.id) : undefined;
      if (!p || p.device !== device) return; // late, cancelled, or not this phone's request
      p.finish(msg.status ?? 200, msg.headers, msg.body);
    });
    ws.on("close", (code) => {
      if (agents.get(device) !== ws) return; // a newer connection owns this device now
      agents.delete(device);
      log(`[-] ${device} disconnected (${code})`);
      for (const p of [...pending.values()]) if (p.device === device) p.failWith(502, `Phone '${device}' disconnected before answering.`);
    });
  });
});

// --- housekeeping ---

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) { ws.terminate(); continue; }
    ws.isAlive = false;
    try { ws.ping(); } catch {}
  }
  const now = Date.now();
  for (const [k, h] of hits) if (now - h.start > 60_000) hits.delete(k);
  for (const id of agents.keys()) if (claims[id]) claims[id].seen = now; // connected phones stay claimed
}, 30_000);
const pruner = setInterval(pruneClaims, 6 * 3_600_000);
pruneClaims();

server.on("error", (e) => {
  console.error(e.code === "EADDRINUSE" ? `error: port ${PORT} is already in use. Set PORT.` : `server error: ${e.message}`);
  process.exit(1);
});
process.on("uncaughtException", (e) => log("[!] uncaught exception:", e.stack || e));
process.on("unhandledRejection", (e) => log("[!] unhandled rejection:", e));

function shutdown(sig) {
  log(`${sig}: shutting down`);
  clearInterval(heartbeat);
  clearInterval(pruner);
  for (const p of [...pending.values()]) p.failWith(503, "Relay is restarting. Retry in a moment.");
  for (const ws of wss.clients) ws.close(1001, "relay shutting down");
  if (saveTimer) { clearTimeout(saveTimer); saveTimer = null; try { fs.writeFileSync(CLAIMS_FILE, JSON.stringify(claims), { mode: 0o600 }); } catch {} }
  server.close(() => process.exit(0));
  setTimeout(() => process.exit(0), 3000).unref();
}
process.on("SIGTERM", () => shutdown("SIGTERM"));
process.on("SIGINT", () => shutdown("SIGINT"));

server.listen(PORT, () => log(`DroidMCP relay on :${PORT} (${AGENT_KEY ? "private: X-Agent-Key required" : "open registration"})`));
