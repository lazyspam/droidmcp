# DroidMCP

Let an AI agent use your **rooted Android phone**. DroidMCP runs an [MCP](https://modelcontextprotocol.io) server on the
phone and gives it a public HTTPS URL, so Claude, Cursor or any other MCP client can see the screen, tap, type, open
apps, read notifications and SMS, manage files and run root shell commands, from anywhere.

```
MCP client ──HTTPS──▶ relay ◀──WebSocket── DroidMCP app (rooted phone)
                      /d/<device-id>/mcp
```

The phone always connects *out* to the relay, so it works on mobile data, behind CGNAT, with no port forwarding.

> ⚠️ Anyone with your URL **and** client token has root on your phone. Read [SECURITY.md](SECURITY.md).

## Tools

| Tool | What it does |
|---|---|
| `screenshot` | Screen as a JPEG, with the scale factor for converting to tap coordinates |
| `ui_tree` | Every on-screen element: text, id, flags and tap center |
| `tap`, `long_press`, `swipe`, `type_text`, `key` | Input. `type_text` handles any language and emoji |
| `launch_app`, `list_apps`, `open_url` | Apps, intents, deep links |
| `notifications` | Current notifications |
| `sms_list`, `send_sms` | Read SMS; send and wait for delivery confirmation |
| `read_file`, `write_file` | Any file (root) |
| `device_info` | Model, Android version, battery, screen, IPs, storage, root status |
| `shell` | Root shell for everything else (`svc wifi enable`, `pm install`, `settings put`, `am start`, `reboot`, …). Can be turned off |

## Quick start

1. **Install the APK** from [Releases](../../releases) on a rooted phone (Magisk, KernelSU or APatch).
2. **Open DroidMCP**, tap **Start server**, and grant root.
   It gets a random device ID and connects to the default relay.
   If the relay is private, enter its agent key first.
3. **Connect your client.** **Copy MCP client config** gives ready-made JSON, or for Claude Code:

   ```bash
   claude mcp add --transport http android https://<relay>/d/<device-id>/mcp \
     --header "Authorization: Bearer <token from the app>"
   ```

To check whether your phone is connected, open `https://<relay>/d/<device-id>/status`.

In your root manager, turn off the superuser toast for DroidMCP, or every tool call shows one.

## Run your own relay

Running your own relay means nobody else can see your traffic.

### Cloudflare Workers (free tier works)

```bash
cd relay-cloudflare
npx wrangler deploy                       # prints https://droidmcp-relay.<you>.workers.dev
npx wrangler secret put AGENT_KEY         # optional: make it private
```

Put that URL (and the agent key, if you set one) into the app.
To use a custom domain, add `"routes": [{ "pattern": "relay.example.com", "custom_domain": true }]` to `wrangler.jsonc`.

### Node / Docker (any VPS)

```bash
cd relay
docker build -t droidmcp-relay .
docker run -d --restart=always -p 8080:8080 -v droidmcp:/data \
  -e AGENT_KEY="$(openssl rand -hex 24)" droidmcp-relay     # omit AGENT_KEY for an open relay
```

Put HTTPS in front of it (e.g. Caddy: `relay.example.com { reverse_proxy localhost:8080 }`).

### No relay at all

The app also serves `http://<phone-ip>:8765/mcp` directly. Use it over LAN or Tailscale, or with
`adb forward tcp:8765 tcp:8765` → `http://localhost:8765/mcp`.

## How relay registration works

- On first launch, each phone generates a random **device ID** (`p-…`) and a **device secret**.
- The first phone to connect with an ID claims it. After that, only the same secret can use it.
  IDs unused for 90 days are released.
- A relay with `AGENT_KEY` set is **private**: phones must also present that key.
- MCP clients authenticate to the **phone** with the client token. The relay just forwards.

## Build from source

```bash
cd android
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Requires JDK 17+ and the Android SDK (compileSdk 36).

CI builds every push. Tagging `vX.Y.Z` publishes a signed release when these repository secrets are set:
`DROIDMCP_KEYSTORE_B64`, `DROIDMCP_KEYSTORE_PASSWORD`, `DROIDMCP_KEY_ALIAS` and `DROIDMCP_KEY_PASSWORD`.

## Limits

- `ui_tree` uses `uiautomator dump`, which fails on secure screens (lock screen, banking apps) and sometimes mid-animation. Use `screenshot` there.
- Apps that set `FLAG_SECURE` show up black in screenshots.
- Some OEM ROMs kill background apps aggressively. Set DroidMCP's battery usage to "unrestricted".
  (With root, the app already whitelists itself from Doze.)

## License

[MIT](LICENSE)
