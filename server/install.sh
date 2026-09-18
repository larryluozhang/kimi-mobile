#!/usr/bin/env bash
#
# Kimi Mobile — one-command server installer
#
# Sets up a Kimi Code daemon ("kimi web") that the Kimi Mobile app
# (Android / iOS / macOS / Windows) can connect to over REST + WebSocket.
#
# Supported platforms: macOS, Debian/Ubuntu-ish Linux (systemd).
#
# The script is idempotent: re-running it detects what already exists and
# only fills the gaps. It never resets an existing token, never duplicates
# autostart entries, and never touches config.toml of an already-configured
# kimi-code installation.
#
# Usage:
#   ./install.sh [--port <port>]
#   ./install.sh --uninstall
#
# Environment overrides:
#   KIMI_CODE_HOME      Data directory (default: ~/.kimi-code)
#   KIMI_PORT           Daemon port (default: 58627)
#   KIMI_VERSION        Pin the kimi-code version for fresh installs
#   KIMI_CODE_PASSWORD  Optional web UI password, baked into the launcher
#
set -euo pipefail

# --------------------------------------------------------------------------
# Constants
# --------------------------------------------------------------------------

KIMI_CODE_HOME="${KIMI_CODE_HOME:-$HOME/.kimi-code}"
CONFIG_TOML="$KIMI_CODE_HOME/config.toml"
TOKEN_FILE="$KIMI_CODE_HOME/server.token"
CONNECT_CARD="$KIMI_CODE_HOME/connect-card.txt"
DEFAULT_PORT=58627
PORT="${KIMI_PORT:-$DEFAULT_PORT}"
PASSWORD="${KIMI_CODE_PASSWORD:-}"
OFFICIAL_INSTALLER="https://code.kimi.com/kimi-code/install.sh"
LAUNCH_LABEL="com.kimi-mobile.server"
PLIST_PATH="$HOME/Library/LaunchAgents/${LAUNCH_LABEL}.plist"
SYSTEMD_UNIT="kimi-mobile-server.service"
SYSTEMD_DIR="$HOME/.config/systemd/user"
LOG_FILE="$KIMI_CODE_HOME/server.log"

OS="$(uname -s)"
KIMI_BIN=""
ADOPTED=0          # 1 if a healthy kimi web already listens on $PORT
TOKEN=""

# --------------------------------------------------------------------------
# Output helpers
# --------------------------------------------------------------------------

if [ -t 1 ]; then
  C_RESET=$'\033[0m'; C_BOLD=$'\033[1m'; C_RED=$'\033[31m'
  C_GREEN=$'\033[32m'; C_YELLOW=$'\033[33m'; C_BLUE=$'\033[34m'
else
  C_RESET=""; C_BOLD=""; C_RED=""; C_GREEN=""; C_YELLOW=""; C_BLUE=""
fi

info()  { printf '%s==>%s %s\n' "$C_BLUE"  "$C_RESET" "$*"; }
ok()    { printf '%s  ok%s %s\n' "$C_GREEN" "$C_RESET" "$*"; }
warn()  { printf '%swarn%s %s\n' "$C_YELLOW" "$C_RESET" "$*" >&2; }
err()   { printf '%serror%s %s\n' "$C_RED" "$C_RESET" "$*" >&2; }
die()   { err "$*"; exit 1; }
step()  { printf '\n%s== %s ==%s\n' "$C_BOLD" "$*" "$C_RESET"; }

have() { command -v "$1" >/dev/null 2>&1; }

# /dev/tty can exist yet be unusable (no controlling terminal) — probe by opening
tty_available() { ( : < /dev/tty ) 2>/dev/null; }

# confirm <question> [default: y|n]  — returns 0 for yes
confirm() {
  local question="$1" default="${2:-n}" reply suffix
  if ! tty_available; then
    [ "$default" = "y" ]
    return
  fi
  suffix="[y/N]"
  [ "$default" = "y" ] && suffix="[Y/n]"
  printf '%s %s ' "$question" "$suffix" > /dev/tty
  read -r reply < /dev/tty || reply=""
  reply="${reply:-$default}"
  case "$reply" in
    y|Y|yes|YES|Yes) return 0 ;;
    *) return 1 ;;
  esac
}

# --------------------------------------------------------------------------
# Small utilities
# --------------------------------------------------------------------------

# urlencode <string> — RFC 3986 percent-encoding (byte-wise, UTF-8 safe)
urlencode() {
  local LC_ALL=C
  local s="$1" out="" c i code
  for (( i = 0; i < ${#s}; i++ )); do
    c="${s:i:1}"
    case "$c" in
      [a-zA-Z0-9._~_-]) out+="$c" ;;
      *)
        printf -v code '%d' "'$c"
        [ "$code" -lt 0 ] && code=$(( code + 256 ))
        printf -v c '%%%02X' "$code"
        out+="$c" ;;
    esac
  done
  printf '%s' "$out"
}

json_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  printf '%s' "$s"
}

xml_escape() {
  local s="$1"
  s="${s//&/&amp;}"
  s="${s//</&lt;}"
  s="${s//>/&gt;}"
  printf '%s' "$s"
}

# Escape a value for a systemd Environment="KEY=value" line
systemd_escape() {
  local s="$1"
  s="${s//\\/\\\\}"
  s="${s//\"/\\\"}"
  s="${s//%/%%}"
  printf '%s' "$s"
}

http_code() { # http_code <url> [curl args...] — "000" when nothing answers
  local url="$1"; shift || true
  # curl prints 000 via -w even when the connection fails
  curl -s -o /dev/null -w '%{http_code}' -m 5 "$@" "$url" 2>/dev/null || true
}

health_probe() { # health_probe <port> — 0 if a healthy kimi web answers
  [ "$(http_code "http://127.0.0.1:$1/api/v1/healthz")" = "200" ]
}

port_free() { # port_free <port>
  if have lsof; then
    ! lsof -nP -iTCP:"$1" -sTCP:LISTEN >/dev/null 2>&1
  else
    # Fall back to "does anything answer TCP at all"
    [ "$(http_code "http://127.0.0.1:$1/")" = "000" ]
  fi
}

lan_ip() {
  local ip=""
  if [ "$OS" = "Darwin" ]; then
    ip="$(ipconfig getifaddr en0 2>/dev/null || true)"
    [ -z "$ip" ] && ip="$(ipconfig getifaddr en1 2>/dev/null || true)"
  else
    ip="$(hostname -I 2>/dev/null | awk '{print $1}' || true)"
  fi
  printf '%s' "$ip"
}

tailscale_ip() {
  if have tailscale; then
    tailscale ip -4 2>/dev/null | head -n 1 || true
  fi
}

resolve_kimi_bin() {
  if have kimi; then
    command -v kimi
    return 0
  fi
  if [ -x "$KIMI_CODE_HOME/bin/kimi" ]; then
    printf '%s\n' "$KIMI_CODE_HOME/bin/kimi"
    return 0
  fi
  return 1
}

provider_configured() {
  [ -f "$CONFIG_TOML" ] && grep -q '^\[providers\.' "$CONFIG_TOML"
}

# --------------------------------------------------------------------------
# Usage / argument parsing
# --------------------------------------------------------------------------

usage() {
  cat <<'EOF'
Kimi Mobile — server installer

Usage:
  install.sh [--port <port>]   Set up the Kimi Code daemon for Kimi Mobile
  install.sh --uninstall       Remove the autostart entry and stop the daemon
  install.sh --help            Show this help

Environment overrides:
  KIMI_CODE_HOME      Data directory (default: ~/.kimi-code)
  KIMI_PORT           Daemon port (default: 58627)
  KIMI_VERSION        Pin the kimi-code version for fresh installs
  KIMI_CODE_PASSWORD  Optional web UI password, baked into the launcher
EOF
}

UNINSTALL=0
while [ $# -gt 0 ]; do
  case "$1" in
    --uninstall) UNINSTALL=1; shift ;;
    --port)
      [ $# -ge 2 ] || die "--port requires a value"
      PORT="$2"; shift 2 ;;
    -h|--help) usage; exit 0 ;;
    *) die "Unknown argument: $1 (see --help)" ;;
  esac
done

case "$PORT" in
  ''|*[!0-9]*) die "Invalid port: $PORT" ;;
esac
[ "$PORT" -ge 1 ] && [ "$PORT" -le 65535 ] || die "Invalid port: $PORT"

# --------------------------------------------------------------------------
# Uninstall
# --------------------------------------------------------------------------

do_uninstall() {
  step "Uninstalling the Kimi Mobile server layer"

  local removed=0

  if [ "$OS" = "Darwin" ]; then
    if [ -f "$PLIST_PATH" ]; then
      launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
      rm -f "$PLIST_PATH"
      ok "Removed LaunchAgent: $PLIST_PATH"
      removed=1
    fi
  elif [ "$OS" = "Linux" ]; then
    if [ -f "$SYSTEMD_DIR/$SYSTEMD_UNIT" ]; then
      if have systemctl; then
        systemctl --user disable --now "$SYSTEMD_UNIT" >/dev/null 2>&1 || true
      fi
      rm -f "$SYSTEMD_DIR/$SYSTEMD_UNIT"
      have systemctl && systemctl --user daemon-reload >/dev/null 2>&1 || true
      ok "Removed systemd user unit: $SYSTEMD_DIR/$SYSTEMD_UNIT"
      removed=1
    fi
  fi

  if [ "$removed" -eq 0 ]; then
    info "No autostart entry found — nothing to remove."
  fi

  if health_probe "$PORT"; then
    warn "A kimi web process still answers on port $PORT."
    warn "It was not started by this installer (or has not exited yet); stop it manually if needed."
  fi

  cat <<EOF

Left intact on purpose:
  - kimi-code itself (binary and $KIMI_CODE_HOME)
  - $CONFIG_TOML
  - $TOKEN_FILE
  - $CONNECT_CARD
Delete those by hand if you want a full removal.
EOF
}

# --------------------------------------------------------------------------
# Step 1: kimi-code installation (Group A vs Group B)
# --------------------------------------------------------------------------

step_detect_or_install() {
  step "Step 1/7 — kimi-code installation"

  if KIMI_BIN="$(resolve_kimi_bin)"; then
    ok "Found kimi binary: $KIMI_BIN"
    if provider_configured; then
      ok "Existing configured installation detected (Group A)."
      info "This script will NOT reinstall kimi-code and will NOT modify $CONFIG_TOML."
    else
      info "kimi-code is installed but no model provider is configured yet."
      info "This script will NOT reinstall kimi-code; it will only offer to add a provider."
    fi
    return
  fi

  info "No kimi-code installation found (Group B) — running the official installer."
  if [ -n "${KIMI_VERSION:-}" ]; then
    info "KIMI_VERSION=$KIMI_VERSION is set and will be honored by the official installer."
  fi
  have curl || die "curl is required but not installed."

  # KIMI_VERSION (if set) is inherited by the official installer.
  curl -fsSL "$OFFICIAL_INSTALLER" | bash

  if ! KIMI_BIN="$(resolve_kimi_bin)"; then
    die "kimi binary still not found after install. Open a new shell (PATH may have changed) and re-run this script."
  fi
  ok "Installed kimi-code: $KIMI_BIN ($("$KIMI_BIN" --version 2>/dev/null || echo 'version unknown'))"
}

# --------------------------------------------------------------------------
# Step 2: API key / model configuration (Group B and unconfigured Group A)
# --------------------------------------------------------------------------

write_api_key_config() {
  local key="$1"
  mkdir -p "$KIMI_CODE_HOME"

  if grep -q '^\[providers\.kimi-mobile\]' "$CONFIG_TOML" 2>/dev/null; then
    ok "Provider 'kimi-mobile' already present in $CONFIG_TOML — leaving it as is."
    return
  fi

  umask 077
  {
    printf '\n[providers.kimi-mobile]\n'
    printf 'type = "kimi"\n'
    printf 'base_url = "https://api.kimi.com/coding/v1"\n'
    printf 'api_key = "%s"\n' "$key"
    printf '\n[models."kimi-mobile/kimi-for-coding"]\n'
    printf 'provider = "kimi-mobile"\n'
    printf 'model = "kimi-for-coding"\n'
  } >> "$CONFIG_TOML"

  # default_model is a top-level key and must precede all tables in TOML,
  # so it goes at the top of the file (only if not already set).
  if ! grep -q '^default_model' "$CONFIG_TOML"; then
    local tmp
    tmp="$(mktemp)"
    {
      printf 'default_model = "kimi-mobile/kimi-for-coding"\n'
      cat "$CONFIG_TOML"
    } > "$tmp"
    cat "$tmp" > "$CONFIG_TOML"
    rm -f "$tmp"
  fi

  chmod 600 "$CONFIG_TOML"
  ok "Wrote provider 'kimi-mobile' and model 'kimi-mobile/kimi-for-coding' to $CONFIG_TOML (mode 600)."
  info "You can adjust the model later by editing $CONFIG_TOML."
}

step_configure_model() {
  step "Step 2/7 — Model provider"

  if provider_configured; then
    ok "Model providers already configured — leaving $CONFIG_TOML untouched."
    return
  fi

  if ! tty_available; then
    warn "No interactive terminal available; skipping model configuration."
    warn "The app cannot chat until a model is configured. Run 'kimi login' or edit $CONFIG_TOML."
    return
  fi

  cat > /dev/tty <<'EOF'

No model provider is configured yet. Choose how to set one up:

  1) Sign in with OAuth (recommended)
     Runs "kimi login": opens a browser/device-code flow. The API key is
     exchanged directly between your browser and the service — this script
     never sees or stores it in plaintext.
  2) Paste an API key
     The key is read without echo and written to config.toml (mode 600).
  3) Skip
     WARNING: the app cannot chat until a model is configured.

EOF
  local choice
  printf 'Select [1/2/3]: ' > /dev/tty
  read -r choice < /dev/tty || choice="3"

  case "$choice" in
    1)
      info "Starting OAuth sign-in (a browser window or device-code prompt will appear)..."
      if "$KIMI_BIN" login < /dev/tty; then
        ok "Sign-in complete."
      else
        warn "'kimi login' did not complete. Re-run this script or run 'kimi login' yourself."
      fi
      ;;
    2)
      local api_key=""
      printf 'Paste API key (input hidden): ' > /dev/tty
      read -r -s api_key < /dev/tty || api_key=""
      printf '\n' > /dev/tty
      if [ -z "$api_key" ]; then
        warn "Empty key — nothing written."
        warn "The app cannot chat until a model is configured."
      else
        write_api_key_config "$api_key"
      fi
      ;;
    *)
      warn "Skipped. The app cannot chat until a model is configured."
      warn "Configure later with 'kimi login' or by editing $CONFIG_TOML."
      ;;
  esac
}

# --------------------------------------------------------------------------
# Step 3: Port selection
# --------------------------------------------------------------------------

step_choose_port() {
  step "Step 3/7 — Port"

  while :; do
    if health_probe "$PORT"; then
      ADOPTED=1
      ok "Port $PORT already serves a healthy kimi web — adopting it (no new process needed)."
      return
    fi
    if port_free "$PORT"; then
      ok "Using port $PORT."
      return
    fi
    warn "Port $PORT is occupied by something that is not a healthy kimi web."
    if tty_available; then
      local new_port=""
      printf 'Enter another port: ' > /dev/tty
      read -r new_port < /dev/tty || new_port=""
      case "$new_port" in
        ''|*[!0-9]*) warn "Not a number, try again." ; continue ;;
      esac
      [ "$new_port" -ge 1 ] && [ "$new_port" -le 65535 ] || { warn "Out of range, try again."; continue; }
      PORT="$new_port"
    else
      die "Port $PORT occupied and no terminal to ask for another. Re-run with --port <port>."
    fi
  done
}

# --------------------------------------------------------------------------
# Step 4: Autostart (launchd on macOS, systemd user unit on Linux)
# --------------------------------------------------------------------------

write_plist() {
  # KIMI_CODE_HOME is always pinned so the daemon uses the same data
  # directory this script resolved; KIMI_CODE_PASSWORD only when set.
  local env_xml
  env_xml="$(cat <<EOF
    <key>EnvironmentVariables</key>
    <dict>
        <key>KIMI_CODE_HOME</key>
        <string>$(xml_escape "$KIMI_CODE_HOME")</string>
$([ -n "$PASSWORD" ] && printf '        <key>KIMI_CODE_PASSWORD</key>\n        <string>%s</string>\n' "$(xml_escape "$PASSWORD")")
    </dict>
EOF
)"

  mkdir -p "$HOME/Library/LaunchAgents" "$KIMI_CODE_HOME"
  cat > "$PLIST_PATH" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
    <key>Label</key>
    <string>${LAUNCH_LABEL}</string>
    <key>ProgramArguments</key>
    <array>
        <string>${KIMI_BIN}</string>
        <string>web</string>
        <string>--host</string>
        <string>--port</string>
        <string>${PORT}</string>
        <string>--no-open</string>
    </array>
${env_xml}
    <key>RunAtLoad</key>
    <true/>
    <key>KeepAlive</key>
    <true/>
    <key>StandardOutPath</key>
    <string>${LOG_FILE}</string>
    <key>StandardErrorPath</key>
    <string>${LOG_FILE}</string>
</dict>
</plist>
EOF
  chmod 600 "$PLIST_PATH"
}

write_systemd_unit() {
  # KIMI_CODE_HOME is always pinned so the daemon uses the same data
  # directory this script resolved; KIMI_CODE_PASSWORD only when set.
  local env_lines
  env_lines="Environment=\"KIMI_CODE_HOME=$(systemd_escape "$KIMI_CODE_HOME")\""
  if [ -n "$PASSWORD" ]; then
    env_lines="$env_lines
Environment=\"KIMI_CODE_PASSWORD=$(systemd_escape "$PASSWORD")\""
  fi

  mkdir -p "$SYSTEMD_DIR" "$KIMI_CODE_HOME"
  cat > "$SYSTEMD_DIR/$SYSTEMD_UNIT" <<EOF
[Unit]
Description=Kimi Mobile server (kimi web)
After=network-online.target
Wants=network-online.target

[Service]
ExecStart=${KIMI_BIN} web --host --port ${PORT} --no-open
${env_lines}
Restart=always
RestartSec=3
StandardOutput=append:${LOG_FILE}
StandardError=append:${LOG_FILE}

[Install]
WantedBy=default.target
EOF
  chmod 600 "$SYSTEMD_DIR/$SYSTEMD_UNIT"
}

step_autostart() {
  step "Step 4/7 — Autostart"

  if [ -n "$PASSWORD" ]; then
    info "KIMI_CODE_PASSWORD is set — it will be baked into the launcher file (mode 600),"
    info "not inherited from your shell environment."
  fi

  local existed=0

  if [ "$OS" = "Darwin" ]; then
    [ -f "$PLIST_PATH" ] && existed=1
    write_plist
    if [ "$existed" -eq 1 ]; then
      ok "Updated existing LaunchAgent in place: $PLIST_PATH"
      launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
      launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH"
      ok "Reloaded LaunchAgent."
    elif [ "$ADOPTED" -eq 1 ]; then
      ok "Wrote LaunchAgent: $PLIST_PATH"
      info "It will start at next login; the currently running daemon was left untouched."
    else
      launchctl bootout "gui/$(id -u)" "$PLIST_PATH" >/dev/null 2>&1 || true
      launchctl bootstrap "gui/$(id -u)" "$PLIST_PATH"
      ok "Installed and started LaunchAgent: $PLIST_PATH"
    fi

  elif [ "$OS" = "Linux" ]; then
    have systemctl || die "systemd not found. Please start '$KIMI_BIN web --host --port $PORT --no-open' with your own supervisor."
    [ -f "$SYSTEMD_DIR/$SYSTEMD_UNIT" ] && existed=1
    write_systemd_unit
    systemctl --user daemon-reload
    systemctl --user enable "$SYSTEMD_UNIT" >/dev/null 2>&1
    if [ "$ADOPTED" -eq 1 ] && [ "$existed" -eq 0 ]; then
      ok "Wrote and enabled systemd user unit: $SYSTEMD_DIR/$SYSTEMD_UNIT"
      info "It will start at next login; the currently running daemon was left untouched."
    else
      systemctl --user restart "$SYSTEMD_UNIT"
      if [ "$existed" -eq 1 ]; then
        ok "Updated and restarted systemd user unit: $SYSTEMD_DIR/$SYSTEMD_UNIT"
      else
        ok "Installed and started systemd user unit: $SYSTEMD_DIR/$SYSTEMD_UNIT"
      fi
    fi
    if ! loginctl enable-linger "$USER" >/dev/null 2>&1; then
      warn "Could not enable lingering automatically."
      warn "To keep the daemon running after you log out, run: sudo loginctl enable-linger $USER"
    else
      ok "Linger enabled (daemon survives logout)."
    fi
  else
    die "Unsupported OS: $OS (supported: macOS, Linux)"
  fi
}

# --------------------------------------------------------------------------
# Step 5: Wait for the daemon, resolve the token
# --------------------------------------------------------------------------

step_daemon_and_token() {
  step "Step 5/7 — Daemon startup and token"

  local i
  for i in $(seq 1 30); do
    if health_probe "$PORT"; then
      ok "kimi web is healthy on port $PORT."
      break
    fi
    if [ "$i" -eq 30 ]; then
      err "The daemon did not become healthy within 30 seconds."
      err "Check the log: $LOG_FILE"
      exit 1
    fi
    sleep 1
  done

  if [ -f "$TOKEN_FILE" ]; then
    ok "Reusing existing token from $TOKEN_FILE (never reset by this script)."
  else
    info "Waiting for the daemon to generate $TOKEN_FILE ..."
    for i in $(seq 1 15); do
      [ -f "$TOKEN_FILE" ] && break
      if [ "$i" -eq 15 ]; then
        err "Token file was not generated: $TOKEN_FILE"
        err "Check the log: $LOG_FILE"
        exit 1
      fi
      sleep 1
    done
    ok "Token generated."
  fi

  TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"
  [ -n "$TOKEN" ] || die "Token file is empty: $TOKEN_FILE"

  if [ "$ADOPTED" -eq 1 ] && [ -n "$PASSWORD" ]; then
    warn "The running daemon predates this setup; restart it (or reboot) for KIMI_CODE_PASSWORD to take effect."
  fi
}

# --------------------------------------------------------------------------
# Step 6: Firewall
# --------------------------------------------------------------------------

step_firewall() {
  step "Step 6/7 — Firewall"

  if [ "$OS" = "Darwin" ]; then
    ok "macOS: no firewall changes needed by default."
    return
  fi

  local sudo_cmd=""
  if [ "$(id -u)" -ne 0 ]; then
    if have sudo; then sudo_cmd="sudo"; fi
  fi

  if have ufw; then
    if confirm "ufw detected. Allow inbound TCP port $PORT? (needed for LAN/Tailnet access)" "y"; then
      # shellcheck disable=SC2086 # sudo_cmd is either empty or exactly "sudo"
      $sudo_cmd ufw allow "${PORT}/tcp"
      ok "ufw: allowed ${PORT}/tcp."
    else
      info "Skipped ufw rule. If the firewall is enabled, run: sudo ufw allow ${PORT}/tcp"
    fi
  elif have firewall-cmd; then
    if confirm "firewalld detected. Allow inbound TCP port $PORT? (needed for LAN/Tailnet access)" "y"; then
      # shellcheck disable=SC2086 # sudo_cmd is either empty or exactly "sudo"
      $sudo_cmd firewall-cmd --permanent --add-port="${PORT}/tcp"
      # shellcheck disable=SC2086 # sudo_cmd is either empty or exactly "sudo"
      $sudo_cmd firewall-cmd --reload
      ok "firewalld: allowed ${PORT}/tcp."
    else
      info "Skipped firewalld rule. To allow later: sudo firewall-cmd --permanent --add-port=${PORT}/tcp && sudo firewall-cmd --reload"
    fi
  else
    info "No ufw/firewalld found — assuming no local firewall. Check cloud security groups if applicable."
  fi
}

# --------------------------------------------------------------------------
# Step 7: Self-check (all three must pass)
# --------------------------------------------------------------------------

step_self_check() {
  step "Step 7/7 — Self-check"

  local c_noauth c_wrong c_right
  c_noauth="$(http_code "http://127.0.0.1:$PORT/api/v1/healthz")"
  c_wrong="$(http_code "http://127.0.0.1:$PORT/api/v1/meta" -H "Authorization: Bearer intentionally-wrong-token")"
  c_right="$(http_code "http://127.0.0.1:$PORT/api/v1/meta" -H "Authorization: Bearer $TOKEN")"

  local failed=0

  if [ "$c_noauth" = "200" ]; then
    ok "GET /api/v1/healthz without auth -> 200"
  else
    err "GET /api/v1/healthz without auth -> $c_noauth (expected 200)"
    failed=1
  fi

  if [ "$c_wrong" = "401" ]; then
    ok "GET /api/v1/meta with a wrong token -> 401"
  else
    err "GET /api/v1/meta with a wrong token -> $c_wrong (expected 401)"
    failed=1
  fi

  if [ "$c_right" = "200" ]; then
    ok "GET /api/v1/meta with the real token -> 200"
  else
    err "GET /api/v1/meta with the real token -> $c_right (expected 200)"
    failed=1
  fi

  if [ "$failed" -ne 0 ]; then
    cat >&2 <<EOF

Diagnosis hints:
  - Is exactly one kimi web process running for $KIMI_CODE_HOME?
    Multiple processes on the same data directory cause split-brain.
  - Does the token in $TOKEN_FILE match the process actually listening on $PORT?
    A daemon started with a different KIMI_CODE_HOME will reject this token.
  - Check the daemon log: $LOG_FILE
  - If you adopted an already-running daemon, make sure it is a kimi web
    instance from this machine and this account.
EOF
    exit 1
  fi
}

# --------------------------------------------------------------------------
# Connect card
# --------------------------------------------------------------------------

print_connect_card() {
  step "Connect card"

  local host_name lan ts base_url deeplink json
  host_name="$(hostname -s 2>/dev/null || hostname)"
  lan="$(lan_ip)"
  ts="$(tailscale_ip)"

  # Prefer Tailscale, then LAN, then loopback for the card's primary URL.
  if [ -n "$ts" ]; then
    base_url="http://$ts:$PORT"
  elif [ -n "$lan" ]; then
    base_url="http://$lan:$PORT"
  else
    base_url="http://127.0.0.1:$PORT"
  fi

  deeplink="kimi-mobile://connect?v=1&name=$(urlencode "$host_name")&url=$(urlencode "$base_url")&token=$(urlencode "$TOKEN")"
  json="$(printf '{"name":"%s","url":"%s","token":"%s"}' "$(json_escape "$host_name")" "$(json_escape "$base_url")" "$TOKEN")"

  umask 077
  cat > "$CONNECT_CARD" <<EOF
# Kimi Mobile — connect card for ${host_name}
# Import this in the app via "Import server" (paste the deeplink or the JSON).
# Keep this file secret: it contains the bearer token.

Deeplink:
${deeplink}

JSON:
${json}
EOF
  chmod 600 "$CONNECT_CARD"

  cat <<EOF
Add this server in the Kimi Mobile app via "Import server":

  Deeplink:
  $deeplink

  JSON:
  $json

Also written to: $CONNECT_CARD (mode 600)
EOF

  if have qrencode; then
    printf '\nScan with the app:\n'
    qrencode -t ANSIUTF8 "$deeplink"
  else
    info "Install 'qrencode' to render the deeplink as a terminal QR code."
  fi

  printf '\n'
  info "Reachable URLs:"
  [ -n "$lan" ] && printf '  LAN:       http://%s:%s\n' "$lan" "$PORT"
  [ -n "$ts" ]  && printf '  Tailscale: http://%s:%s\n' "$ts" "$PORT"
  printf '  Local:     http://127.0.0.1:%s\n' "$PORT"

  cat <<EOF

${C_YELLOW}SECURITY WARNING${C_RESET}
  The daemon binds 0.0.0.0 so your phone/desktop can reach it.
  Use it on your LAN or Tailnet ONLY. Do NOT expose this port to the
  public internet (no port forwarding, no public security-group rule).
  The bearer token is the only thing protecting full agent access to
  this machine — treat the connect card like a password.
EOF
}

# --------------------------------------------------------------------------
# Main
# --------------------------------------------------------------------------

main() {
  if [ "$UNINSTALL" -eq 1 ]; then
    do_uninstall
    exit 0
  fi

  printf '%sKimi Mobile — server setup%s\n' "$C_BOLD" "$C_RESET"
  printf 'Platform: %s | KIMI_CODE_HOME: %s\n' "$OS" "$KIMI_CODE_HOME"

  have curl || die "curl is required but not installed."
  mkdir -p "$KIMI_CODE_HOME"

  step_detect_or_install
  step_configure_model
  step_choose_port
  step_autostart
  step_daemon_and_token
  step_firewall
  step_self_check
  print_connect_card

  printf '\n%sDone.%s Re-run this script any time — it only fills gaps.\n' "$C_BOLD" "$C_RESET"
}

main
