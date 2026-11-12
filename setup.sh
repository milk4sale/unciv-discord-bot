#!/usr/bin/env bash
# One-time setup (safe to re-run):
#   1. fetches Unciv (the game engine) at a known-good commit
#   2. wires the bot module into Unciv's Gradle build
#   3. patches one Unciv developer assertion that can crash AI turns
#   4. builds the bot
set -euo pipefail
cd "$(dirname "$0")"

UNCIV_COMMIT="0f2eb314a17213da0c0ce3b9d9c4693e2ed6ef96"

if ! command -v java >/dev/null; then
  cat >&2 <<'MSG'
Java not found. Install a JDK 21, for example:
  Arch / CachyOS / Manjaro:  sudo pacman -S jdk21-openjdk
  Debian / Ubuntu:           sudo apt install openjdk-21-jdk
  Fedora:                    sudo dnf install java-21-openjdk-devel
  macOS (Homebrew):          brew install openjdk@21
MSG
  exit 1
fi
JAVA_MAJOR=$(java -version 2>&1 | sed -n 's/.*version "\([0-9]*\).*/\1/p' | head -1)
if [ -n "$JAVA_MAJOR" ] && [ "$JAVA_MAJOR" -lt 21 ]; then
  echo "Java $JAVA_MAJOR found, but Java 21 or newer is required." >&2; exit 1
fi

# Fetch just the pinned Unciv commit (shallow, ~100 MB)
if [ ! -d unciv/.git ]; then
  echo "Downloading Unciv..."
  git init -q unciv
  git -C unciv remote add origin https://github.com/yairm210/Unciv.git
fi
if [ "$(git -C unciv rev-parse HEAD 2>/dev/null || true)" != "$UNCIV_COMMIT" ]; then
  git -C unciv fetch -q --depth 1 origin "$UNCIV_COMMIT"
  git -C unciv checkout -q -f FETCH_HEAD
fi

# Include ../bot as the ":civbot" Gradle project
if ! grep -q 'civbot' unciv/settings.gradle.kts; then
  cat >> unciv/settings.gradle.kts <<'EOF'

// --- added by civbot/setup.sh ---
include(":civbot")
project(":civbot").projectDir = file("../bot")
EOF
fi

# Turn Unciv's "City within distance" developer assertion (which can crash AI turns when a
# player settles next to an AI's starting settler) into a harmless skipped move.
SUA=unciv/core/src/com/unciv/logic/automation/unit/SpecificUnitAutomation.kt
sed -i.bak 's|throw Exception("City within distance")|return // civbot patch: was throw Exception("City within distance")|' "$SUA" && rm -f "$SUA.bak"

echo "Building (the first build downloads Gradle and libraries — a few minutes)..."
./unciv/gradlew -p unciv :civbot:installDist --console=plain -q

echo
echo "Done!"
[ -f .env ] || echo "Next: cp .env.example .env   then put your DISCORD_TOKEN in .env"
echo "Run:  ./run.sh          (or ./scripts/install-service.sh to run in the background)"
echo "Test without Discord:  ./run.sh --selftest"
