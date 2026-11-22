# Security

DroidMCP gives an MCP client **root control of a phone**. Treat the client token like the phone's root password.

## Trust model

| Secret | Lives on | Grants |
|---|---|---|
| Client token | the phone; you copy it into your MCP client | running every tool on the phone |
| Device secret | the phone; sent only to the relay | holding your device ID on the relay |
| Agent key (optional) | the relay and phones you configure | connecting phones to a private relay |

- The relay checks only the device secret and the optional agent key. It cannot run tools without the client token.
- **The relay operator can see your traffic.** HTTPS ends at the relay, which must read requests to forward them,
  and that includes the client token. Only use a relay whose operator you trust. For full control, deploy your own
  (one click, free tier; see the README).
- **Scripts in Linux (beta)** reach every tool through a loopback-only server on the phone (127.0.0.1, random port).
  Its token is made fresh each time the service starts and is written to `/etc/droidmcp/api` inside each distro,
  which only the app can read. These calls skip "Approve on this phone": only the AI's own `linux_exec` commands
  and your terminals run there. They still appear in the bubble and in Activity, from the client `script`.
- **Code mode on the relay (beta, off by default)** runs JavaScript sent by the client in a Cloudflare Dynamic
  Worker with no network access. That code can call the phone's tools only through the relay, with the client's own
  token, which stays outside the sandbox. The phone still checks the token, approvals and logging for every call.
- The phone always shows a notification while the server runs. The app has no hidden or stealth mode, and won't add one.

## Use only on devices you own

Installing DroidMCP on someone else's device to monitor or control it without their knowledge may be illegal and
is against the intent of this project.

## Reporting a vulnerability

Please report security problems privately through GitHub's "Report a vulnerability" (Security tab), not in public issues.
