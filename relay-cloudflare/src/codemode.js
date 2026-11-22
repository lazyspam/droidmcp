// Code mode: the AI writes one JavaScript snippet that calls many phone tools, and it runs here on the
// relay in a sandboxed Dynamic Worker (no network). Only the snippet's return value and console output
// go back to the model, instead of one model round trip (and one full ui_tree) per tap.
//
//   MCP client --(/d/X/code/mcp)--> Worker --> sandbox --(env.DROID RPC)--> DroidApi --> Durable Object X --> phone
//
// Tools offered to the client: `execute` (with TypeScript types for every phone tool) and `screenshot`,
// whose image the model has to see itself. Everything else (initialize, ping, ...) is passed to the phone,
// so its token check, approvals and activity log apply to every call the snippet makes.
//
// Needs the worker_loaders binding (Workers Paid plan). Without it these endpoints answer 501.

import { WorkerEntrypoint } from "cloudflare:workers";

const FORWARD_HEADERS = ["authorization", "content-type", "accept", "mcp-session-id", "mcp-protocol-version"];
const MAX_CALLS = 200;
const RUN_TIMEOUT_MS = 300_000;
const SANDBOX_VERSION = "1";
const BASE_HEADERS = { "X-Content-Type-Options": "nosniff", "Cache-Control": "no-store" };

function rpcReply(id, payload, headers = {}) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id, ...payload }), {
    status: 200,
    headers: { ...BASE_HEADERS, "Content-Type": "application/json", ...headers },
  });
}

function rpcFail(status, id, message, headers = {}) {
  return new Response(JSON.stringify({ jsonrpc: "2.0", id: id ?? null, error: { code: -32000, message } }), {
    status,
    headers: { ...BASE_HEADERS, "Content-Type": "application/json", ...headers },
  });
}

function pickHeaders(from) {
  const out = {};
  for (const h of FORWARD_HEADERS) {
    const v = from instanceof Headers ? from.get(h) : from[h];
    if (v) out[h] = v;
  }
  out["content-type"] = "application/json";
  out.accept = "application/json, text/event-stream";
  return out;
}

/** Sends one JSON-RPC message to the phone through its Durable Object, like a normal /d/X/mcp request. */
function toPhone(env, device, headers, method, body) {
  return env.DEVICE.get(env.DEVICE.idFromName(device)).fetch(
    new Request(`https://relay/d/${device}/mcp`, { method, headers, body: method === "GET" || method === "DELETE" ? undefined : body }),
  );
}

async function phoneRpc(env, device, headers, msg) {
  const res = await toPhone(env, device, headers, "POST", JSON.stringify(msg));
  const text = await res.text();
  let json;
  try { json = JSON.parse(text); } catch { throw new Error(`Phone replied HTTP ${res.status}: ${text.slice(0, 300)}`); }
  if (res.status === 401) throw new Error("The phone rejected the client's token (401). Check the Authorization header.");
  if (json.error) throw new Error(typeof json.error === "string" ? json.error : json.error.message || JSON.stringify(json.error));
  if (!res.ok && !json.result) throw new Error(`Phone replied HTTP ${res.status}`);
  return { result: json.result, res };
}

/**
 * What the sandbox gets as env.DROID. The client's headers (bearer token, session) live in props on this
 * side of the RPC boundary, so code in the sandbox can call tools but never reads the token.
 */
export class DroidApi extends WorkerEntrypoint {
  async call(name, args) {
    if (typeof name !== "string" || !/^[A-Za-z0-9_]{1,64}$/.test(name)) throw new Error(`Bad tool name: ${name}`);
    const { device, headers } = this.ctx.props;
    const { result } = await phoneRpc(this.env, device, headers, {
      jsonrpc: "2.0", id: crypto.randomUUID(), method: "tools/call", params: { name, arguments: args ?? {} },
    });
    const content = Array.isArray(result?.content) ? result.content : [];
    return {
      text: content.filter((c) => c.type === "text").map((c) => c.text).join("\n"),
      images: content.filter((c) => c.type === "image").map((c) => ({ data: c.data, mimeType: c.mimeType })),
      isError: !!result?.isError,
    };
  }
}

// ---- TypeScript types for the execute tool ----

function tsType(s) {
  if (!s || typeof s !== "object") return "unknown";
  if (Array.isArray(s.enum)) return s.enum.map((v) => JSON.stringify(v)).join(" | ");
  switch (s.type) {
    case "integer": case "number": return "number";
    case "string": return "string";
    case "boolean": return "boolean";
    case "array": return `${tsType(s.items)}[]`;
    case "object": return s.properties ? objType(s, "") : "Record<string, unknown>";
    default: return "unknown";
  }
}

function objType(schema, indent) {
  const props = schema?.properties || {};
  const req = new Set(schema?.required || []);
  const keys = Object.keys(props);
  if (!keys.length) return "{}";
  const lines = keys.map((k) => {
    const d = props[k]?.description ? `${indent}    /** ${String(props[k].description).replace(/\*\//g, "*\\/")} */\n` : "";
    return `${d}${indent}    ${/^[A-Za-z_$][\w$]*$/.test(k) ? k : JSON.stringify(k)}${req.has(k) ? "" : "?"}: ${tsType(props[k])};`;
  });
  return `{\n${lines.join("\n")}\n${indent}  }`;
}

const IMAGE_TOOLS = new Set(["screenshot"]);

export function typesFor(tools) {
  const methods = tools.map((t) => {
    const schema = t.inputSchema || {};
    const optional = !(schema.required || []).length;
    const ret = IMAGE_TOOLS.has(t.name) ? "Promise<{ text: string; images: Image[] }>" : "Promise<string>";
    const doc = String(t.description || "").replace(/\*\//g, "*\\/");
    return `  /** ${doc} */\n  ${t.name}(args${optional ? "?" : ""}: ${objType(schema, "  ")}): ${ret};`;
  });
  return `interface Image { data: string; mimeType: string } // base64
/** One on-screen element from ui_tree. tap() taps its center. */
interface UiNode { cls: string; text: string; desc: string; id: string; flags: string[]; x: number; y: number;
  depth: number; window: string; clickable: boolean; tap(): Promise<string> }
/** Filters: strings match substrings, case-insensitive (exact: true for exact). any matches text, desc or id. */
interface Match { text?: string; desc?: string; id?: string; cls?: string; any?: string; clickable?: boolean; exact?: boolean }
declare const droid: {
${methods.join("\n")}
  /** ui_tree, parsed. */
  ui(all?: boolean): Promise<UiNode[]>;
  /** First element on screen matching q, or null. Pass nodes to search a list you already have. */
  find(q: Match & { nodes?: UiNode[] }): Promise<UiNode | null>;
  find_all(q: Match & { nodes?: UiNode[] }): Promise<UiNode[]>;
  /** Polls ui_tree until an element matches (default timeout 10s, interval 0.7s); throws on timeout. */
  wait_for(q: Match & { timeout?: number; interval?: number }): Promise<UiNode>;
  /** Finds an element (waiting up to timeout seconds if given) and taps it; throws if none. */
  tap_on(q: Match & { timeout?: number }): Promise<UiNode>;
  /** Raw tool call: never throws for a failed tool. */
  call(name: string, args?: object): Promise<{ text: string; images: Image[]; isError: boolean }>;
  sleep(seconds: number): Promise<void>;
};`;
}

function executeTool(tools) {
  return {
    name: "execute",
    description:
      "Run JavaScript against this Android phone's tools in one go. Write the body of an async function: call tools with " +
      "`await droid.<tool>({...})`, use loops, `droid.wait_for` and `droid.tap_on`, and `return` only what you need. " +
      "The return value (as JSON) and console.log output come back; the intermediate results stay out of your context. " +
      "Use it for multi-step work: open an app, wait for a button, tap it, type, read the result; or to search a long " +
      "ui_tree. A failed tool throws (catch it, or use droid.call). Return `await droid.screenshot()` to see the screen. " +
      `Runs in a sandbox with no network access; at most ${MAX_CALLS} tool calls and ${RUN_TIMEOUT_MS / 60000} minutes per run.\n\n` +
      "Example:\n```js\nawait droid.launch_app({ package: \"com.android.settings\" });\nawait droid.tap_on({ text: \"Battery\", timeout: 5 });\n" +
      "const n = await droid.wait_for({ text: \"%\" });\nreturn n.text;\n```\n\nAPI:\n```ts\n" + typesFor(tools) + "\n```",
    inputSchema: {
      type: "object",
      properties: { code: { type: "string", description: "Body of an async JavaScript function; use `return` for the result." } },
      required: ["code"],
    },
  };
}

// ---- the sandbox ----

// Runs inside the Dynamic Worker. Plain JS kept in a string; no template literals inside.
const PRELUDE = String.raw`
import { WorkerEntrypoint } from "cloudflare:workers";

class ToolError extends Error { constructor(m) { super(m); this.name = "ToolError"; } }
const LINE = /^(\s*)(\S+)(?: text="(.*?)")?(?: desc="(.*?)")?(?: id=(\S+))?(?: \[([^\]]*)\])? @\((-?\d+),(-?\d+)\)$/;

function makeDroid(DROID, maxCalls) {
  let calls = 0;
  const sleep = (sec) => new Promise((r) => setTimeout(r, sec * 1000));
  async function call(name, args) {
    if (++calls > maxCalls) throw new ToolError("Too many tool calls in one run (max " + maxCalls + ").");
    return DROID.call(name, args || {});
  }
  async function run(name, args) {
    const r = await call(name, args);
    if (r.isError) throw new ToolError(name + ": " + r.text);
    return r.images.length ? { text: r.text, images: r.images } : r.text;
  }
  function parse_ui(text) {
    const nodes = []; let window = "";
    for (const line of String(text).split("\n")) {
      if (line.startsWith("window: ") || line.startsWith("package: ")) { window = line.slice(line.indexOf(": ") + 2); continue; }
      const m = LINE.exec(line);
      if (!m) continue;
      const n = { cls: m[2], text: m[3] || "", desc: m[4] || "", id: m[5] || "",
        flags: (m[6] || "").split(",").filter(Boolean), x: +m[7], y: +m[8], depth: m[1].length / 2, window };
      n.clickable = n.flags.includes("clickable");
      Object.defineProperty(n, "tap", { value: () => run("tap", { x: n.x, y: n.y }) });
      nodes.push(n);
    }
    return nodes;
  }
  async function ui(all) { return parse_ui(await run("ui_tree", { all: !!all })); }
  function matches(n, q) {
    const ok = (have, want) => want == null || (q.exact ? have === want : have.toLowerCase().includes(String(want).toLowerCase()));
    if (q.any != null && !(ok(n.text, q.any) || ok(n.desc, q.any) || ok(n.id, q.any))) return false;
    if (q.clickable != null && n.clickable !== q.clickable) return false;
    return ok(n.text, q.text) && ok(n.desc, q.desc) && ok(n.id, q.id) && ok(n.cls, q.cls);
  }
  const want = (q) => JSON.stringify(Object.fromEntries(["text", "desc", "id", "cls", "any"].filter((k) => q[k] != null).map((k) => [k, q[k]])));
  async function find_all(q) { q = q || {}; return (q.nodes || await ui()).filter((n) => matches(n, q)); }
  async function find(q) { return (await find_all(q))[0] || null; }
  async function wait_for(q) {
    q = q || {};
    const timeout = q.timeout ?? 10, interval = q.interval ?? 0.7, end = Date.now() + timeout * 1000;
    for (;;) {
      const n = await find(q).catch((e) => { if (String(e.message).startsWith("Too many")) throw e; return null; });
      if (n) return n;
      if (Date.now() >= end) throw new ToolError("wait_for: nothing matching " + want(q) + " appeared within " + timeout + "s");
      await sleep(interval);
    }
  }
  async function tap_on(q) {
    q = q || {};
    const n = q.timeout ? await wait_for(q) : await find(q);
    if (!n) throw new ToolError("tap_on: no element matching " + want(q) + " on screen");
    await n.tap();
    return n;
  }
  const api = { call, ui, parse_ui, find, find_all, wait_for, tap_on, sleep, ToolError, count: () => calls };
  return new Proxy(api, {
    get(t, name) {
      if (name in t) return t[name];
      if (typeof name !== "string" || name === "then" || !/^[a-z][a-z0-9_]*$/.test(name)) return undefined;
      return (args) => run(name, args);
    },
  });
}

function fmt(v) {
  if (typeof v === "string") return v;
  if (v instanceof Error) return v.stack || String(v);
  try { return JSON.stringify(v, null, 2) ?? String(v); } catch { return String(v); }
}

export default class extends WorkerEntrypoint {
  async run(maxCalls) {
    const logs = [];
    const log = (...a) => { if (logs.length < 500) logs.push(a.map(fmt).join(" ")); };
    const sandboxConsole = { log, info: log, warn: log, error: log, debug: log };
    const droid = makeDroid(this.env.DROID, maxCalls);
    try {
      const result = await __user(droid, sandboxConsole);
      let json;
      try { json = JSON.stringify(result === undefined ? null : result); } catch (e) { json = JSON.stringify(String(result)); }
      return { ok: true, result: json, logs, calls: droid.count() };
    } catch (e) {
      return { ok: false, error: e && e.stack ? String(e.stack) : String(e), logs, calls: droid.count() };
    }
  }
}
`;

async function sha256hex(s) {
  const b = new Uint8Array(await crypto.subtle.digest("SHA-256", new TextEncoder().encode(s)));
  return [...b].map((x) => x.toString(16).padStart(2, "0")).join("");
}

async function execute(env, ctx, device, headers, code) {
  if (typeof code !== "string" || !code.trim()) return toolResult("execute needs `code`: the body of an async function.", true);
  if (!ctx.exports?.DroidApi) {
    return toolResult("This relay can't run code mode: ctx.exports is unavailable (needs a compatibility_date from 2025-11 on, " +
      "or the enable_ctx_exports flag).", true);
  }
  const source = PRELUDE + "\nasync function __user(droid, console) {\n" + code + "\n}\n";
  // Same code and same caller -> same isolate; the caller's headers are part of the key so no one shares another's binding.
  const id = "run-" + (await sha256hex([SANDBOX_VERSION, device, JSON.stringify(headers), source].join("\u0000")));
  const worker = env.LOADER.get(id, async () => ({
    compatibilityDate: "2026-09-01",
    mainModule: "main.js",
    modules: { "main.js": source },
    env: { DROID: ctx.exports.DroidApi({ props: { device, headers } }) },
    globalOutbound: null, // no fetch() or connect() from the sandbox
  }));

  let out;
  let timer;
  try {
    out = await Promise.race([
      worker.getEntrypoint().run(MAX_CALLS),
      new Promise((_, reject) => { timer = setTimeout(() => reject(new Error(`timed out after ${RUN_TIMEOUT_MS / 1000}s`)), RUN_TIMEOUT_MS); }),
    ]);
  } catch (e) {
    const msg = String(e?.message || e);
    const hint = /SyntaxError|Unexpected|Uncaught/.test(msg) ? " (check the code's syntax: it is the body of an async function)" : "";
    return toolResult(`Code didn't run${hint}: ${msg}`, true);
  } finally {
    clearTimeout(timer);
  }

  const parts = [];
  if (out.logs?.length) parts.push("console:\n" + out.logs.join("\n"));
  let images = [];
  if (out.ok) {
    let value = JSON.parse(out.result);
    // A returned screenshot (or anything with images) is shown as images, not base64 text.
    if (value && typeof value === "object" && Array.isArray(value.images)) {
      images = value.images.filter((i) => i && typeof i.data === "string").slice(0, 5);
      const { images: _, ...rest } = value;
      value = Object.keys(rest).length === 1 && "text" in rest ? rest.text : rest;
    }
    parts.push("result: " + (typeof value === "string" ? value : JSON.stringify(value, null, 2)));
  } else {
    parts.push("error: " + out.error);
  }
  parts.push(`(${out.calls} tool call${out.calls === 1 ? "" : "s"})`);
  const content = [{ type: "text", text: parts.join("\n\n") }];
  for (const i of images) content.push({ type: "image", data: i.data, mimeType: i.mimeType || "image/png" });
  return { content, isError: !out.ok };
}

function toolResult(text, isError = false) {
  return { content: [{ type: "text", text }], isError };
}

const INSTRUCTIONS =
  "Code mode: instead of one tool call per step, write JavaScript with the `execute` tool. It calls the phone's tools " +
  "(droid.tap, droid.ui_tree, droid.launch_app, ...) plus helpers (droid.ui, droid.find, droid.wait_for, droid.tap_on) and " +
  "returns only what you return. Do whole sequences in one execute call; use `screenshot` when you need to see the screen.\n\n";

/** Handles /d/<device>/code[/mcp]. */
export async function codeMode(request, env, ctx, device) {
  if (!env.LOADER) {
    return rpcFail(501, null, "Code mode is off on this relay: it needs the worker_loaders binding, which needs the " +
      `Workers Paid plan (see wrangler.jsonc). Use /d/${device}/mcp instead.`);
  }
  const headers = pickHeaders(request.headers);
  if (request.method === "DELETE") return toPhone(env, device, headers, "DELETE");
  if (request.method !== "POST") return rpcFail(405, null, "Use POST.", { Allow: "POST, DELETE" });

  const body = await request.text();
  let msg;
  try { msg = JSON.parse(body); } catch { return rpcFail(400, null, "Parse error: expected a JSON-RPC message."); }
  if (Array.isArray(msg)) return rpcFail(400, null, "Code mode doesn't take JSON-RPC batches; send one message per request.");
  const id = msg?.id ?? null;

  try {
    switch (msg?.method) {
      case "initialize": {
        const params = { ...(msg.params || {}) };
        const ci = { ...(params.clientInfo || {}) };
        ci.name = `${ci.name || "AI client"} (code mode)`;
        params.clientInfo = ci;
        const { result, res } = await phoneRpc(env, device, headers, { ...msg, params });
        const sid = res.headers.get("mcp-session-id");
        result.instructions = INSTRUCTIONS + (result.instructions || "");
        return rpcReply(id, { result }, sid ? { "Mcp-Session-Id": sid } : {});
      }
      case "tools/list": {
        const { result } = await phoneRpc(env, device, headers, msg);
        const tools = Array.isArray(result?.tools) ? result.tools : [];
        const direct = tools.filter((t) => t.name === "screenshot");
        return rpcReply(id, { result: { tools: [executeTool(tools), ...direct] } });
      }
      case "tools/call": {
        if (msg.params?.name === "execute") {
          return rpcReply(id, { result: await execute(env, ctx, device, headers, msg.params?.arguments?.code) });
        }
        break; // any phone tool called directly still works
      }
    }
  } catch (e) {
    return rpcFail(502, id, `Code mode: ${e.message}`);
  }
  const res = await toPhone(env, device, headers, "POST", body);
  return new Response(res.body, res);
}
