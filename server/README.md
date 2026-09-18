# Server Setup for Kimi Mobile

`install.sh` is a one-command installer that turns a macOS or Linux machine into a server the Kimi Mobile app can connect to. It sets up the [Kimi Code](https://github.com/MoonshotAI/kimi-code) daemon (`kimi web`, REST + WebSocket) with a bearer token, autostart, and a connect card you can import into the app.

Supported platforms: macOS (launchd) and Linux with systemd (user unit). Requires `bash` and `curl`.

## One-line install

```bash
curl -fsSL https://raw.githubusercontent.com/larryluozhang/kimi-mobile/main/server/install.sh | bash
```

Or clone this repo and run `server/install.sh` directly. Flags and environment overrides:

| Option / variable | Default | Purpose |
|---|---|---|
| `--port <port>` | `58627` | Daemon port |
| `--uninstall` | — | Remove autostart entry and stop the daemon |
| `KIMI_CODE_HOME` | `~/.kimi-code` | kimi-code data directory |
| `KIMI_PORT` | `58627` | Same as `--port` |
| `KIMI_VERSION` | latest | Pin the kimi-code version on fresh installs |
| `KIMI_CODE_PASSWORD` | unset | Optional web UI password, baked into the launcher file |

## What it does

The script is idempotent: re-running it detects what already exists and only fills the gaps. It never resets an existing token, never duplicates autostart entries, and never touches a working `config.toml`.

Steps, in order:

1. **kimi-code detection** — looks for the `kimi` binary (`$PATH` or `$KIMI_CODE_HOME/bin/kimi`) and a configured provider in `config.toml`.
2. **Model provider** — offers an API-key import menu only if nothing is configured yet.
3. **Port** — uses the default port; if occupied, probes it and either adopts a healthy existing `kimi web` or asks for another port.
4. **Autostart** — writes/updates `~/Library/LaunchAgents/com.kimi-mobile.server.plist` (macOS, KeepAlive + RunAtLoad) or `~/.config/systemd/user/kimi-mobile-server.service` (Linux, `Restart=always`, with a `loginctl enable-linger` hint).
5. **Daemon + token** — starts the daemon, waits for health, reuses `$KIMI_CODE_HOME/server.token` if present (never rotates it).
6. **Firewall** — on Linux offers a `ufw`/`firewall-cmd` rule for the port; on macOS no action is needed by default.
7. **Self-check** — verifies `healthz` returns 200 without auth, 401 with a wrong token, 200 with the right token. Any failure exits non-zero with diagnosis hints.

## Group A: you already use kimi-code

If you already have kimi-code installed and configured (TUI, web UI, VS Code — anything), the script detects it and **does not reinstall kimi-code and does not modify `config.toml`**. Your existing setup is left exactly as it is; the script only adds the daemon/autostart layer and the connect card.

If kimi-code is installed but has no model provider yet, the script still skips the install and only offers to add a provider.

## Group B: fresh machine

If no kimi-code is found, the script:

1. Runs the official installer (`https://code.kimi.com/kimi-code/install.sh`, honoring `KIMI_VERSION` if set).
2. Offers three ways to configure a model:
   - **Sign in with OAuth (recommended)** — runs `kimi login`, which opens a browser/device-code flow. The key is exchanged between your browser and the service; the script never sees or stores it in plaintext.
   - **Paste an API key** — read without echo, written as a minimal provider+model block in `config.toml` (mode `600`).
   - **Skip** — with a clear warning that the app cannot chat until a model is configured.

## Connecting the app

At the end the script prints a **connect card** (also saved to `$KIMI_CODE_HOME/connect-card.txt`, mode `600`):

- a deeplink: `kimi-mobile://connect?v=1&name=<host>&url=<base>&token=<token>`
- the equivalent JSON: `{"name":...,"url":...,"token":...}`
- a terminal QR code of the deeplink, if `qrencode` is installed

In the Kimi Mobile app, choose **Import server** and paste the deeplink or the JSON (or scan the QR code). The card's URL prefers the machine's Tailscale address when available, then its LAN address. Both are printed, along with the loopback URL.

## Security notes

- The daemon binds `0.0.0.0` so your phone/desktop can reach it. **Use it on your LAN or Tailnet only — do not expose the port to the public internet** (no router port forwarding, no public cloud security-group rule).
- The bearer token is the only thing protecting full agent access to the machine. Treat `server.token` and `connect-card.txt` like passwords (both are mode `600`).
- If `KIMI_CODE_PASSWORD` is set at install time, it is written into the launcher file itself (plist/systemd unit, mode `600`) — it never depends on your shell environment.
- Run exactly one `kimi web` per data directory; multiple servers on the same `~/.kimi-code` cause split-brain state.

## Uninstall

```bash
./install.sh --uninstall
```

Removes the autostart entry (LaunchAgent or systemd user unit) and stops the daemon. kimi-code itself, `config.toml`, `server.token`, and the connect card are left intact — delete them by hand if you want a full removal.

## Troubleshooting

- **Self-check fails with 401 on the real token** — the process listening on the port was probably started with a different `KIMI_CODE_HOME`; its token does not match `$KIMI_CODE_HOME/server.token`.
- **Daemon does not come up** — check the log at `$KIMI_CODE_HOME/server.log`.
- **Daemon dies after logout on Linux** — run `sudo loginctl enable-linger $USER`.
- **Multiple servers / phantom busy sessions** — see `docs/PROTOCOL-NOTES.md` in this repo for known pitfalls.
