"""Call DroidMCP's phone tools from Python. Installed by the DroidMCP app (standard library only).

    import droid
    droid.launch_app(package="com.android.settings")   # every tool is a function returning its text
    droid.tap_on(text="Network")                       # find an element in ui_tree and tap it
    droid.wait_for(text="Wi-Fi", timeout=10)           # poll until it shows up
    nodes = droid.ui()                                 # ui_tree as a list of Node
    droid.call("tap", x=1, y=2)                        # raw MCP result dict, never raises

Failed tools raise droid.ToolError. `droid help` in a shell prints the whole API.
"""

import base64
import json
import os
import re
import time
import urllib.error
import urllib.request

__all__ = ["ToolError", "Node", "call", "run", "tools", "ui", "find", "find_all", "wait_for", "tap_on",
           "screenshot_png", "sleep"]

API_FILE = "/etc/droidmcp/api"


class ToolError(Exception):
    """A tool ran and failed (isError), or the call itself was rejected."""


def _endpoint():
    url, tok = os.environ.get("DROID_API"), os.environ.get("DROID_TOKEN", "")
    if url:
        return url, tok
    try:
        with open(API_FILE) as f:
            lines = f.read().split("\n")
        return lines[0].strip(), lines[1].strip()
    except OSError:
        raise ToolError("DroidMCP isn't running (no %s). Start it in the app." % API_FILE) from None


def _request(method, path, body=None, timeout=3600):
    url, tok = _endpoint()
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(url + path, data=data, method=method,
                                 headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            return json.loads(r.read().decode())
    except urllib.error.HTTPError as e:
        try:
            msg = json.loads(e.read().decode()).get("error", str(e))
        except Exception:
            msg = str(e)
        raise ToolError(msg) from None
    except urllib.error.URLError as e:
        raise ToolError("Can't reach DroidMCP at %s (is the app running?): %s" % (url, e.reason)) from None


def call(tool, args=None, **kwargs):
    """Runs a tool and returns the raw MCP result: {"content": [...], "isError": bool}. Never raises for tool failures."""
    a = dict(args or {})
    a.update(kwargs)
    return _request("POST", "/call/" + tool, a)


def _text(result):
    return "\n".join(c.get("text", "") for c in result.get("content", []) if c.get("type") == "text")


def run(tool, args=None, **kwargs):
    """Runs a tool and returns its text. Raises ToolError if it failed."""
    r = call(tool, args, **kwargs)
    if r.get("isError"):
        raise ToolError("%s: %s" % (tool, _text(r)))
    return _text(r)


def tools():
    """Tool definitions: [{"name", "description", "inputSchema"}]."""
    return _request("GET", "/tools")


def sleep(seconds):
    time.sleep(seconds)


def screenshot_png(path=None, max_width=720):
    """Takes a screenshot and returns the image bytes (PNG/JPEG as the phone sends it); saves it to path if given."""
    r = call("screenshot", max_width=max_width)
    if r.get("isError"):
        raise ToolError("screenshot: " + _text(r))
    for c in r.get("content", []):
        if c.get("type") == "image":
            data = base64.b64decode(c["data"])
            if path:
                with open(path, "wb") as f:
                    f.write(data)
            return data
    raise ToolError("screenshot returned no image: " + _text(r))


class Node(dict):
    """One ui_tree element. Keys (also attributes): cls, text, desc, id, flags, x, y, depth, window."""

    __getattr__ = dict.get

    @property
    def clickable(self):
        return "clickable" in self["flags"]

    def tap(self):
        return run("tap", x=self["x"], y=self["y"])

    def __repr__(self):
        bits = [self["cls"]]
        for k in ("text", "desc", "id"):
            if self[k]:
                bits.append("%s=%r" % (k, self[k]))
        if self["flags"]:
            bits.append("[" + ",".join(self["flags"]) + "]")
        return "<Node %s @(%d,%d)>" % (" ".join(bits), self["x"], self["y"])


_LINE = re.compile(r'^(?P<ind>\s*)(?P<cls>\S+)(?: text="(?P<text>.*?)")?(?: desc="(?P<desc>.*?)")?'
                   r'(?: id=(?P<id>\S+))?(?: \[(?P<flags>[^\]]*)\])? @\((?P<x>-?\d+),(?P<y>-?\d+)\)$')


def parse_ui(text):
    """Parses ui_tree's text into a list of Node."""
    nodes, window = [], ""
    for line in text.split("\n"):
        if line.startswith("window: ") or line.startswith("package: "):
            window = line.split(": ", 1)[1]
            continue
        m = _LINE.match(line)
        if not m:
            continue
        nodes.append(Node(cls=m["cls"], text=m["text"] or "", desc=m["desc"] or "", id=m["id"] or "",
                          flags=[f for f in (m["flags"] or "").split(",") if f], x=int(m["x"]), y=int(m["y"]),
                          depth=len(m["ind"]) // 2, window=window))
    return nodes


def ui(all=False):
    """The current screen's elements (ui_tree, parsed)."""
    return parse_ui(run("ui_tree", all=all))


def _matches(n, text, desc, id, cls, any, clickable, exact):
    def ok(have, want):
        if want is None:
            return True
        return have == want if exact else want.lower() in have.lower()
    if any is not None and not (ok(n["text"], any) or ok(n["desc"], any) or ok(n["id"], any)):
        return False
    if clickable is not None and n.clickable != clickable:
        return False
    return ok(n["text"], text) and ok(n["desc"], desc) and ok(n["id"], id) and ok(n["cls"], cls)


def find_all(text=None, desc=None, id=None, cls=None, any=None, clickable=None, exact=False, nodes=None):
    """Elements matching every given filter. Strings match substrings, case-insensitive, unless exact=True.
    any= matches text, desc or id."""
    nodes = ui() if nodes is None else nodes
    return [n for n in nodes if _matches(n, text, desc, id, cls, any, clickable, exact)]


def find(text=None, desc=None, id=None, cls=None, any=None, clickable=None, exact=False, nodes=None):
    """The first matching element, or None."""
    found = find_all(text, desc, id, cls, any, clickable, exact, nodes)
    return found[0] if found else None


def wait_for(text=None, desc=None, id=None, cls=None, any=None, clickable=None, exact=False, timeout=10, interval=0.7):
    """Polls the screen until an element matches; returns it. Raises ToolError after timeout seconds."""
    end = time.time() + timeout
    while True:
        try:
            n = find(text, desc, id, cls, any, clickable, exact)
        except ToolError:
            n = None  # the screen can be unreadable for a moment during transitions
        if n is not None:
            return n
        if time.time() >= end:
            want = {k: v for k, v in dict(text=text, desc=desc, id=id, cls=cls, any=any).items() if v is not None}
            raise ToolError("wait_for: nothing matching %s appeared within %ss" % (want, timeout))
        time.sleep(interval)


def tap_on(text=None, desc=None, id=None, cls=None, any=None, clickable=None, exact=False, timeout=0):
    """Finds an element (waiting up to timeout seconds) and taps its center. Returns the Node."""
    if timeout:
        n = wait_for(text, desc, id, cls, any, clickable, exact, timeout=timeout)
    else:
        n = find(text, desc, id, cls, any, clickable, exact)
    if n is None:
        want = {k: v for k, v in dict(text=text, desc=desc, id=id, cls=cls, any=any).items() if v is not None}
        raise ToolError("tap_on: no element matching %s on screen" % want)
    n.tap()
    return n


def __getattr__(name):
    """droid.<tool>(**args) runs that tool and returns its text."""
    if name.startswith("_"):
        raise AttributeError(name)

    def tool(args=None, **kwargs):
        return run(name, args, **kwargs)
    tool.__name__ = name
    return tool
