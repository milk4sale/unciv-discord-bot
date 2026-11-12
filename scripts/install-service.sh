#!/usr/bin/env bash
# Installs CivBot as a systemd *user* service (Linux): starts on login, restarts on crash.
#   ./scripts/install-service.sh            install + start
#   ./scripts/install-service.sh --remove   stop + uninstall
set -euo pipefail
DIR="$(cd "$(dirname "$0")/.." && pwd)"
UNIT="$HOME/.config/systemd/user/civbot.service"

if [ "${1:-}" = "--remove" ]; then
  systemctl --user disable --now civbot 2>/dev/null || true
  rm -f "$UNIT"; systemctl --user daemon-reload
  echo "CivBot service removed."; exit 0
fi

[ -x "$DIR/bot/build/install/civbot/bin/civbot" ] || { echo "Build first: ./setup.sh" >&2; exit 1; }
[ -s "$DIR/.env" ] || { echo "Create .env with your DISCORD_TOKEN first (cp .env.example .env)" >&2; exit 1; }

mkdir -p "$(dirname "$UNIT")"
cat > "$UNIT" <<UNIT
[Unit]
Description=CivBot - Unciv in Discord
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
ExecStart=$DIR/run.sh
Restart=on-failure
RestartSec=10
Nice=5

[Install]
WantedBy=default.target
UNIT

systemctl --user daemon-reload
systemctl --user enable --now civbot
echo "CivBot is running. Logs: journalctl --user -u civbot -f"
echo "Tip: 'loginctl enable-linger' keeps it running while you're logged out."
