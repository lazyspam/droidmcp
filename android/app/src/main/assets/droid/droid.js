// Call DroidMCP's phone tools from Node. Installed by the DroidMCP app (no dependencies).
//
//   const droid = require("droid")          // or: import droid from "droid"
//   await droid.launch_app({ package: "com.android.settings" })   // every tool, resolves to its text
//   await droid.tap_on({ text: "Network" })                       // find in ui_tree and tap
//   await droid.wait_for({ text: "Wi-Fi", timeout: 10 })
//   const nodes = await droid.ui()
//   await droid.call("tap", { x: 1, y: 2 })                       // raw MCP result, never rejects for tool failures
//
// Failed tools reject with a ToolError. `droid help` in a shell prints the whole API.
"use strict";

const fs = require("fs");
const http = require("http");

const API_FILE = "/etc/droidmcp/api";

class ToolError extends Error {
  constructor(msg) { super(msg); this.name = "ToolError"; }
}

function endpoint() {
  if (process.env.DROID_API) return [process.env.DROID_API, process.env.DROID_TOKEN || ""];
  let lines;
  try { lines = fs.readFileSync(API_FILE, "utf8").split("\n"); }
  catch { throw new ToolError(`DroidMCP isn't running (no ${API_FILE}). Start it in the app.`); }
  return [lines[0].trim(), (lines[1] || "").trim()];
}

function request(method, path, body) {
  const [base, tok] = endpoint();
  const data = body === undefined ? null : Buffer.from(JSON.stringify(body));
  return new Promise((resolve, reject) => {
    const req = http.request(base + path, {
      method,
      headers: { Authorization: "Bearer " + tok, "Content-Type": "application/json", ...(data ? { "Content-Length": data.length } : {}) },
    }, (res) => {
      const chunks = [];
      res.on("data", (c) => chunks.push(c));
      res.on("end", () => {
        let j;
        try { j = JSON.parse(Buffer.concat(chunks).toString("utf8")); }
        catch { return reject(new ToolError(`Bad reply from DroidMCP (HTTP ${res.statusCode})`)); }
        if (res.statusCode !== 200) return reject(new ToolError(j.error || `HTTP ${res.statusCode}`));
        resolve(j);
      });
    });
    req.on("error", (e) => reject(new ToolError(`Can't reach DroidMCP at ${base} (is the app running?): ${e.message}`)));
    if (data) req.write(data);
    req.end();
  });
}

const textOf = (r) => (r.content || []).filter((c) => c.type === "text").map((c) => c.text).join("\n");
const sleep = (sec) => new Promise((r) => setTimeout(r, sec * 1000));

/** Raw MCP result: { content: [...], isError }. */
function call(tool, args = {}) { return request("POST", "/call/" + tool, args); }

/** A tool's text; rejects if it failed. */
async function run(tool, args = {}) {
  const r = await call(tool, args);
  if (r.isError) throw new ToolError(`${tool}: ${textOf(r)}`);
  return textOf(r);
}

function tools() { return request("GET", "/tools"); }

async function screenshot_png(path, max_width = 720) {
  const r = await call("screenshot", { max_width });
  if (r.isError) throw new ToolError("screenshot: " + textOf(r));
  const img = (r.content || []).find((c) => c.type === "image");
  if (!img) throw new ToolError("screenshot returned no image: " + textOf(r));
  const buf = Buffer.from(img.data, "base64");
  if (path) fs.writeFileSync(path, buf);
  return buf;
}

const LINE = /^(\s*)(\S+)(?: text="(.*?)")?(?: desc="(.*?)")?(?: id=(\S+))?(?: \[([^\]]*)\])? @\((-?\d+),(-?\d+)\)$/;

/** ui_tree text -> [{ cls, text, desc, id, flags, x, y, depth, window, clickable, tap() }] */
function parse_ui(text) {
  const nodes = [];
  let window = "";
  for (const line of text.split("\n")) {
    if (line.startsWith("window: ") || line.startsWith("package: ")) { window = line.slice(line.indexOf(": ") + 2); continue; }
    const m = LINE.exec(line);
    if (!m) continue;
    const n = {
      cls: m[2], text: m[3] || "", desc: m[4] || "", id: m[5] || "",
      flags: (m[6] || "").split(",").filter(Boolean), x: +m[7], y: +m[8], depth: m[1].length / 2, window,
    };
    n.clickable = n.flags.includes("clickable");
    Object.defineProperty(n, "tap", { value: () => run("tap", { x: n.x, y: n.y }) });
    nodes.push(n);
  }
  return nodes;
}

async function ui(all = false) { return parse_ui(await run("ui_tree", { all })); }

function matches(n, q) {
  const ok = (have, want) => want == null || (q.exact ? have === want : have.toLowerCase().includes(String(want).toLowerCase()));
  if (q.any != null && !(ok(n.text, q.any) || ok(n.desc, q.any) || ok(n.id, q.any))) return false;
  if (q.clickable != null && n.clickable !== q.clickable) return false;
  return ok(n.text, q.text) && ok(n.desc, q.desc) && ok(n.id, q.id) && ok(n.cls, q.cls);
}

const want = (q) => JSON.stringify(Object.fromEntries(["text", "desc", "id", "cls", "any"].filter((k) => q[k] != null).map((k) => [k, q[k]])));

/** Elements matching { text, desc, id, cls, any, clickable, exact }. Strings match substrings, case-insensitive. */
async function find_all(q = {}) { return (q.nodes || await ui()).filter((n) => matches(n, q)); }
async function find(q = {}) { return (await find_all(q))[0] || null; }

async function wait_for(q = {}) {
  const timeout = q.timeout ?? 10, interval = q.interval ?? 0.7;
  const end = Date.now() + timeout * 1000;
  for (;;) {
    const n = await find(q).catch(() => null);
    if (n) return n;
    if (Date.now() >= end) throw new ToolError(`wait_for: nothing matching ${want(q)} appeared within ${timeout}s`);
    await sleep(interval);
  }
}

async function tap_on(q = {}) {
  const n = q.timeout ? await wait_for(q) : await find(q);
  if (!n) throw new ToolError(`tap_on: no element matching ${want(q)} on screen`);
  await n.tap();
  return n;
}

const api = { ToolError, call, run, tools, ui, parse_ui, find, find_all, wait_for, tap_on, screenshot_png, sleep };

// droid.<tool>(args) runs that tool and resolves to its text.
module.exports = new Proxy(api, {
  get(target, name) {
    if (name in target) return target[name];
    if (typeof name !== "string" || name === "then" || !/^[a-z][a-z0-9_]*$/.test(name)) return undefined;
    return (args = {}) => run(name, args);
  },
});
