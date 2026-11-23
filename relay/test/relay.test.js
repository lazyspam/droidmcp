// Starts server.js on a free port and checks the errors MCP clients get when the phone can't answer.
//   npm test

import { test, before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import net from "node:net";
import { fileURLToPath } from "node:url";
import { WebSocket } from "ws";

const SERVER = fileURLToPath(new URL("../server.js", import.meta.url));
const SECRET = "s".repeat(40);
let proc, base, dir;

const freePort = () =>
  new Promise((resolve) => {
    const s = net.createServer().listen(0, () => { const { port } = s.address(); s.close(() => resolve(port)); });
  });

before(async () => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "relay-test-"));
  const port = await freePort();
  base = `http://127.0.0.1:${port}`;
  proc = spawn(process.execPath, [SERVER], {
    env: { ...process.env, PORT: String(port), CLAIMS_FILE: path.join(dir, "claims.json"), REQUEST_TIMEOUT_MS: "1500" },
    stdio: ["ignore", "pipe", "inherit"],
  });
  await new Promise((resolve) => proc.stdout.on("data", (d) => { if (String(d).includes("relay on")) resolve(); }));
});

after(() => {
  proc.kill();
  fs.rmSync(dir, { recursive: true, force: true });
});

const rpc = (device, id = 7) =>
  fetch(`${base}/d/${device}/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json, text/event-stream" },
    body: JSON.stringify({ jsonrpc: "2.0", id, method: "tools/list" }),
  });

/** A fake phone. onRequest(msg, ws) handles each forwarded request. */
function phone(device, onRequest) {
  const ws = new WebSocket(`${base.replace("http", "ws")}/agent?device=${device}`, {
    headers: { Authorization: `Bearer ${SECRET}` },
  });
  ws.on("message", (d) => onRequest(JSON.parse(String(d)), ws));
  return new Promise((resolve, reject) => { ws.on("open", () => resolve(ws)); ws.on("error", reject); });
}

test("unknown or offline device: 503 with Retry-After and a JSON-RPC error carrying the id", async () => {
  const r = await rpc("nobody-here", 42);
  assert.equal(r.status, 503);
  assert.equal(r.headers.get("retry-after"), "30");
  assert.match(r.headers.get("content-type"), /application\/json/);
  const j = await r.json();
  assert.equal(j.jsonrpc, "2.0");
  assert.equal(j.id, 42);
  assert.equal(j.error.code, -32000);
  assert.match(j.error.message, /^Phone is offline/);
});

test("GET (SSE stream) to an offline device: 503 with id null", async () => {
  const r = await fetch(`${base}/d/nobody-here/mcp`, { headers: { Accept: "text/event-stream" } });
  assert.equal(r.status, 503);
  assert.equal((await r.json()).id, null);
});

test("connected phone: the reply is passed through", async () => {
  const ws = await phone("dev-ok", (m, ws) =>
    ws.send(JSON.stringify({ id: m.id, status: 200, headers: { "content-type": "application/json" }, body: '{"ok":1}' })));
  const r = await rpc("dev-ok");
  assert.equal(r.status, 200);
  assert.deepEqual(await r.json(), { ok: 1 });
  ws.close();
});

test("phone disconnects mid-request: 504 with a JSON-RPC error", async () => {
  await phone("dev-drop", (_m, ws) => ws.close());
  const r = await rpc("dev-drop", "abc");
  assert.equal(r.status, 504);
  const j = await r.json();
  assert.equal(j.id, "abc");
  assert.match(j.error.message, /^Phone didn't answer in time: it disconnected/);
});

test("phone never answers: 504 after the timeout", async () => {
  const ws = await phone("dev-slow", () => {});
  const r = await rpc("dev-slow", 9);
  assert.equal(r.status, 504);
  const j = await r.json();
  assert.equal(j.id, 9);
  assert.match(j.error.message, /^Phone didn't answer in time: no reply within/);
  ws.close();
});

test("after the phone goes away the device is offline again", async () => {
  const ws = await phone("dev-gone", () => {});
  ws.close();
  await new Promise((r) => ws.on("close", r));
  await new Promise((r) => setTimeout(r, 100));
  assert.equal((await rpc("dev-gone")).status, 503);
});
