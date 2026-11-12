#!/usr/bin/env bash
# Starts the bot. Pass --selftest to play a few offline turns and write panel images (no Discord needed).
set -euo pipefail
HOME_DIR="$(cd "$(dirname "$0")" && pwd)"
BIN="$HOME_DIR/bot/build/install/civbot/bin/civbot"
[ -x "$BIN" ] || { echo "Not built yet — run ./setup.sh first." >&2; exit 1; }
# Unciv loads its rules with relative paths, so run from its assets folder.
cd "$HOME_DIR/unciv/android/assets"
export JAVA_OPTS="${JAVA_OPTS:-} -Dcivbot.home=$HOME_DIR"
exec "$BIN" "$@"
