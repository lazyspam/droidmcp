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
- The phone always shows a notification while the server runs. The app has no hidden or stealth mode, and won't add one.

## Use only on devices you own

Installing DroidMCP on someone else's device to monitor or control it without their knowledge may be illegal and
is against the intent of this project.

## Reporting a vulnerability

Please report security problems privately through GitHub's "Report a vulnerability" (Security tab), not in public issues.
