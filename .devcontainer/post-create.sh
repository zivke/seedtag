#!/usr/bin/env bash
set -euo pipefail

# Named volumes mount root-owned the first time.
sudo chown -R vscode:vscode /home/vscode/.claude /home/vscode/.android /home/vscode/.gradle

# --- Claude Code -------------------------------------------------------------
# Native install, no Node.js: the launcher at ~/.local/bin/claude symlinks into
# ~/.local/share/claude/versions/. Claude Code then checks for updates at startup
# and periodically while running, installing in the background — so this only has
# to establish a current baseline after a rebuild.
#
# The channel passed here becomes the default for those auto-updates:
#   latest — every release as it ships (default)
#   stable — roughly a week behind, skips releases with major regressions
if command -v claude >/dev/null 2>&1; then
  claude update || true
else
  curl -fsSL https://claude.ai/install.sh | bash -s "${CLAUDE_CHANNEL:-latest}"
fi
claude --version || true

# --- signing key -------------------------------------------------------------
# Deliberately not here. `make build` makes an unsigned release APK, signed on
# the machine that holds the key with tools/sign-release.sh, so nothing in this
# container can read the key. Warn if an old setup still names it.
ENV_FILE="$(dirname "$0")/../.env"
if [ -f "$ENV_FILE" ] && grep -qE '^(KEYSTORE|KEY_PASS|KEY_ALIAS)=' "$ENV_FILE"; then
  cat <<'WARN'

!! .env still sets KEYSTORE / KEY_PASS / KEY_ALIAS. Release signing no longer
!! happens in the container: remove those lines (and the key from the
!! android-keys volume, once it's backed up elsewhere).

WARN
fi

# --- Android SDK + Gradle ----------------------------------------------------
echo ">> Android SDK: $ANDROID_HOME"
ls "$ANDROID_HOME/build-tools" "$ANDROID_HOME/platforms" | sed 's/^/     /'
adb version | head -n 1
echo "sdk.dir=$ANDROID_HOME" > "$(dirname "$0")/../local.properties"

# Fetch the Gradle distribution and dependencies once, into the gradle-cache
# volume, so the first `make` isn't a surprise download. Never fails the create.
( cd "$(dirname "$0")/.." && ./gradlew --quiet help >/dev/null 2>&1 ) \
  && echo ">> Gradle ready" \
  || echo "!! Gradle warm-up failed (offline?). The first make will retry."

cat <<'EOF'
>> Next:
     make test                           # crypto core (JVM) + seedtag.py vectors
     make debug                          # seedtag-debug.apk, no key needed
     make build                          # unsigned release seedtag-unsigned.apk
     make connect DEVICE_IP=<device ip>  # adb over the network, once per session
     make run-debug                      # install the debug APK and open the app

   Then: claude   (browser OAuth, once per devcontainer)
EOF
