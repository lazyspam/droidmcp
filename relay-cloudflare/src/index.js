// DroidMCP relay on Cloudflare Workers.
//
//   phone --(WebSocket /agent?device=X)--> Worker --> Durable Object "X" <--(HTTPS /d/X/mcp)-- MCP client
//
// Each device ID maps to one Durable Object. It holds that phone's WebSocket and forwards every MCP
// HTTP request to the phone as a frame, then turns the phone's reply frame back into the HTTP response.
//
// Phone auth:
//   - Every phone sends its own device secret (Authorization: Bearer <secret>). The first phone to connect
//     with a device ID claims it; later connections must present the same secret. Unused IDs are released
//     after 90 days without a connection.
//   - Optional AGENT_KEY secret makes the relay private: phones must also send X-Agent-Key.
// Client auth: the phone checks the MCP client's bearer token itself.
// Code mode (beta, /d/X/code/mcp, needs Workers Paid): see codemode.js.

import { DurableObject } from "cloudflare:workers";
import { codeMode } from "./codemode.js";

// ctx.exports.DroidApi is the binding code mode hands its sandbox.
export { DroidApi } from "./codemode.js";

const TIMEOUT_MS = 610_000; // longer than the phone's 600s max shell timeout
const MAX_BODY = 50 * 1024 * 1024;
const MAX_PENDING = 32;
const FORWARD_HEADERS = ["authorization", "content-type", "accept", "mcp-session-id", "mcp-protocol-version", "last-event-id"];
const RETURN_HEADERS = /^(content-type|mcp-session-id|www-authenticate|allow|retry-after)$/i;
const DEVICE_RE = /^[A-Za-z0-9_-]{1,64}$/;
const CLAIM_TTL_DAYS = 90;
const RATE_PER_MIN = 240; // MCP requests per device per minute
const BASE_HEADERS = { "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store" };

/**
 * Per-IP limit using the rate limiter bindings in wrangler.jsonc (AGENT_LIMIT, CLIENT_LIMIT).
 * A relay deployed without them just skips this check.
 */
async function ipLimited(limiter, request) {
  if (!limiter) return false;
  const { success } = await limiter.limit({ key: request.headers.get("CF-Connecting-IP") || "?" });
  return !success;
}

function rpcId(body) {
  try {
    const m = JSON.parse(body);
    return m && !Array.isArray(m) && "id" in m ? m.id : null;
  } catch {
    return null;
  }
}

/** Error reply that MCP clients show as a readable message. */
function fail(status, message, body = "", headers = {}) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id: rpcId(body), error: { code: -32000, message } }), {
    status,
    headers: { ...BASE_HEADERS, "Content-Type": "application/json", ...headers },
  });
}

async function sha256(s) {
  return new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s || "")));
}

async function safeEqual(a, b) {
  const [x, y] = await Promise.all([sha256(a), sha256(b)]);
  return crypto.subtle.timingSafeEqual(x, y);
}

function hex(bytes) {
  return [...bytes].map((b) => b.toString(16).padStart(2, "0")).join("");
}

export default {
  async fetch(request, env, ctx) {
    try {
      const url = new URL(request.url);

      if (url.pathname === "/" || url.pathname === "/health") {
        return Response.json({ ok: true, service: "droidmcp-relay" });
      }

      if (url.pathname === "/agent") {
        if (await ipLimited(env.AGENT_LIMIT, request)) {
          return new Response("Too many connection attempts. Retry in a minute.", { status: 429 });
        }
        if (request.headers.get("Upgrade")?.toLowerCase() !== "websocket") {
          return new Response("Expected a WebSocket upgrade", { status: 426 });
        }
        const device = url.searchParams.get("device") || "";
        if (!DEVICE_RE.test(device)) {
          return new Response("Bad device ID: use 1-64 letters, digits, - or _", { status: 400 });
        }
        const secret = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
        if (env.AGENT_KEY && !(await safeEqual(request.headers.get("X-Agent-Key") || "", env.AGENT_KEY))) {
          console.log(`rejected '${device}': wrong or missing agent key (private relay)`);
          return new Response("This relay is private: wrong or missing agent key", { status: 401 });
        }
        if (secret.length < 32) return new Response("Missing device secret", { status: 401 });
        return env.DEVICE.get(env.DEVICE.idFromName(device)).fetch(request);
      }

      // Admin (private relays only): release a device ID so another phone can claim it.
      //   curl -X DELETE -H "X-Agent-Key: $KEY" https://<relay>/admin/d/<device>/claim
      const adm = url.pathname.match(/^\/admin\/d\/([A-Za-z0-9_-]{1,64})\/claim$/);
      if (adm) {
        if (!env.AGENT_KEY || !(await safeEqual(request.headers.get("X-Agent-Key") || "", env.AGENT_KEY))) {
          return fail(401, "Admin endpoints need the relay's agent key (and only exist on private relays).");
        }
        if (request.method !== "DELETE") return fail(405, "Use DELETE.");
        return env.DEVICE.get(env.DEVICE.idFromName(adm[1])).fetch(new Request("https://do/admin/release", { method: "POST" }));
      }

      const m = url.pathname.match(/^\/d\/([A-Za-z0-9_-]{1,64})(\/.*)?$/);
      if (!m) return fail(404, "Not found. MCP endpoints look like /d/<device>/mcp.");
      if (await ipLimited(env.CLIENT_LIMIT, request)) return fail(429, "Too many requests. Slow down.", "", { "Retry-After": "10" });
      const len = Number(request.headers.get("Content-Length") || 0);
      if (len > MAX_BODY) return fail(413, `Request body too large (max ${MAX_BODY / 1024 / 1024} MB).`);
      if (m[2] === "/code" || m[2] === "/code/mcp") return await codeMode(request, env, ctx, m[1]);
      return env.DEVICE.get(env.DEVICE.idFromName(m[1])).fetch(request);
    } catch (e) {
      console.error("worker error", e);
      return fail(500, `Relay error: ${e.message}`);
    }
  },
};

export class Device extends DurableObject {
  constructor(ctx, env) {
    super(ctx, env);
    this.pending = new Map(); // request id -> resolve(reply)
    this.window = { start: 0, count: 0 };
  }

  rateLimited() {
    const now = Date.now();
    if (now - this.window.start > 60_000) this.window = { start: now, count: 0 };
    return ++this.window.count > RATE_PER_MIN;
  }

  phone() {
    return this.ctx.getWebSockets("phone").find((ws) => ws.readyState === WebSocket.OPEN);
  }

  async fetch(request) {
    const url = new URL(request.url);
    if (url.pathname === "/agent") return this.acceptPhone(request, url);
    if (url.pathname === "/admin/release") {
      await this.ctx.storage.deleteAll();
      for (const ws of this.ctx.getWebSockets("phone")) {
        try { ws.close(4001, "device ID released by relay admin"); } catch {}
      }
      return Response.json({ released: true });
    }

    const device = url.pathname.split("/")[2];
    const rest = url.pathname.slice(`/d/${device}`.length) || "/";

    if (rest === "/status") return Response.json({ device, connected: !!this.phone() });

    const body = request.method === "GET" || request.method === "HEAD" ? "" : await request.text();
    if (body.length > MAX_BODY) return fail(413, "Request body too large.", "");

    const ws = this.phone();
    if (!ws) {
      return fail(502, `Phone '${device}' is offline: it is not connected to the relay. ` +
        "Check the DroidMCP app is running and shows 'Relay: connected'.", body);
    }
    if (this.rateLimited()) {
      return fail(429, `Rate limit: max ${RATE_PER_MIN} requests per minute per device.`, body, { "Retry-After": "10" });
    }
    if (this.pending.size >= MAX_PENDING) {
      return fail(429, `Too many requests in flight for '${device}'. Retry shortly.`, body, { "Retry-After": "2" });
    }

    const id = crypto.randomUUID();
    const headers = {};
    for (const h of FORWARD_HEADERS) {
      const v = request.headers.get(h);
      if (v) headers[h] = v;
    }

    const reply = await new Promise((resolve) => {
      const timer = setTimeout(() => {
        this.pending.delete(id);
        resolve({ status: 504, error: `Phone '${device}' did not answer within ${TIMEOUT_MS / 1000}s.` });
      }, TIMEOUT_MS);
      this.pending.set(id, (r) => {
        clearTimeout(timer);
        this.pending.delete(id);
        resolve(r);
      });
      try {
        ws.send(JSON.stringify({ id, method: request.method, path: rest + url.search, headers, body }));
      } catch (e) {
        this.pending.get(id)?.({ status: 502, error: `Could not reach phone '${device}': ${e.message}` });
      }
    });

    if (reply.error) return fail(reply.status, reply.error, body);
    const out = new Headers({ ...BASE_HEADERS, "Content-Type": "application/json" });
    for (const [k, v] of Object.entries(reply.headers || {})) {
      if (RETURN_HEADERS.test(k) && typeof v === "string") {
        try { out.set(k, v); } catch { /* invalid header value from phone: drop it */ }
      }
    }
    const status = Number.isInteger(reply.status) && reply.status >= 200 && reply.status < 600 ? reply.status : 502;
    // 202/204 must not carry a body.
    return new Response(status === 202 || status === 204 ? null : (typeof reply.body === "string" ? reply.body : ""), { status, headers: out });
  }

  async acceptPhone(request, url) {
    const secret = (request.headers.get("Authorization") || "").replace(/^Bearer\s+/i, "");
    const hash = hex(await sha256(secret));
    const claimed = await this.ctx.storage.get("secretHash");
    if (claimed && claimed !== hash) {
      console.log(`device '${url.searchParams.get("device")}' is claimed by another phone`);
      return new Response("Device ID is already claimed by another phone. Generate a new device ID in the app.", { status: 403 });
    }
    if (!claimed) await this.ctx.storage.put("secretHash", hash);
    // Connected: cancel the release timer.
    await this.ctx.storage.deleteAlarm();

    // One phone per device ID: a new connection replaces the old one.
    for (const old of this.ctx.getWebSockets("phone")) {
      try { old.close(4000, "replaced by a new connection"); } catch {}
    }
    const [client, server] = Object.values(new WebSocketPair());
    // Hibernation API: the object can sleep between requests without dropping the phone's socket.
    this.ctx.acceptWebSocket(server, ["phone"]);
    console.log(`phone '${url.searchParams.get("device")}' connected`);
    return new Response(null, { status: 101, webSocket: client });
  }

  async webSocketMessage(ws, data) {
    if (typeof data !== "string") return;
    let msg;
    try {
      msg = JSON.parse(data);
    } catch {
      console.log("phone sent a non-JSON frame; ignored");
      return;
    }
    if (msg && typeof msg.id === "string") this.pending.get(msg.id)?.(msg);
  }

  async webSocketClose(ws, code) {
    console.log(`phone disconnected (${code})`);
    try { ws.close(code === 1005 || code === 1006 ? 1000 : code, "closed"); } catch {}
    if (this.phone()) return; // a replacement connection is already up
    for (const resolve of [...this.pending.values()]) {
      resolve({ status: 502, error: "Phone disconnected before answering." });
    }
    // Release the device ID if the phone never comes back.
    await this.ctx.storage.setAlarm(Date.now() + CLAIM_TTL_DAYS * 86_400_000);
  }

  async alarm() {
    if (this.phone()) return;
    console.log("releasing unused device ID");
    await this.ctx.storage.deleteAll();
  }

  async webSocketError(ws, error) {
    console.log(`phone socket error: ${error}`);
  }
}
