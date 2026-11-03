#!/usr/bin/env bash
set -euo pipefail

# Named volumes mount root-owned the first time.
sudo chown -R vscode:vscode /home/vscode/.claude /home/vscode/.android \
                            /home/vscode/.android-keys /home/vscode/.gradle

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
# `make build` (release) signs with the key that KEYSTORE in .env names and
# refuses to build without it: Android will not install an APK over one signed
# with a different key, so a key is never generated here. `make debug` works
# without it (Android's debug key).
ENV_FILE="$(dirname "$0")/../.env"
if [ -z "${KEYSTORE:-}" ] && [ -f "$ENV_FILE" ]; then
  KEYSTORE="$(sed -n 's/^KEYSTORE=//p' "$ENV_FILE" | tail -n 1 | tr -d '\r' | sed "s/^[\"']//; s/[\"']\$//")"
fi
chmod 700 "$HOME/.android-keys"
if [ -z "${KEYSTORE:-}" ]; then
  cat <<'WARN'

!! No KEYSTORE in .env yet, so `make build` (release) will fail; `make debug`
!! works. Create .env in the repository root with the key's path and password:
!!
!!   KEYSTORE=/home/vscode/.android-keys/<your-key>.jks
!!   KEY_PASS=<password>

WARN
elif [ -f "$KEYSTORE" ]; then
  chmod 600 "$KEYSTORE"
  echo ">> Signing key: $KEYSTORE"
else
  cat <<'WARN'

!! The signing key named in .env is not in the android-keys volume yet, so
!! `make build` will fail. Copy your key into it:
!!
!!   docker run --rm -v android-keys:/k -v "$HOME/<dir-with-your-key>":/src:ro \
!!     alpine sh -c 'cp /src/<your-key>.jks /k/ && chown 1000:1000 /k/<your-key>.jks'
!!
!! Then back that key up somewhere durable.

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
     make build                          # signed release seedtag.apk (needs .env)
     make connect DEVICE_IP=<device ip>  # adb over the network, once per session
     make run                            # install on the device and open the app

   Then: claude   (browser OAuth, once per devcontainer)
EOF
