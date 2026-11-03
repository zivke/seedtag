# Devcontainer

VS Code devcontainer for SeedTag: JDK, Android SDK, Gradle cache, adb, Python
for `seedtag.py` and Claude Code, none of it installed on the host.

## Before the first release build: your signing key

Android only installs an update over an existing app if both are signed with
the same key. `make build` signs the release APK with the key that `KEYSTORE`
in `.env` names, kept in the `android-keys` volume (`~/.android-keys`), and
stops with an error if it is missing. `make debug` doesn't need it.

Seed the volume with your key (the same volume luxtheme uses, so if it's
already there, skip this):

```
docker volume create android-keys
docker run --rm -v android-keys:/k -v "$HOME/<dir-with-your-key>":/src:ro \
  alpine sh -c 'cp /src/<your-key>.jks /k/ && chown 1000:1000 /k/<your-key>.jks'
```

Then create `.env` in the repository root (it is in `.gitignore`):

```sh
KEYSTORE=/home/vscode/.android-keys/<your-key>.jks
KEY_PASS=<password>
```

`KEY_ALIAS` can be left out when the keystore holds a single key. The key
password must be the same as the keystore password.

Debug and release builds are signed with different keys, so switching between
them on the device needs `make uninstall` first.

## What is in the image

Ubuntu 24.04 (`mcr.microsoft.com/devcontainers/base`), OpenJDK 21, Python 3
with `cryptography` and `argon2-cffi`, and an Android SDK in `/opt/android-sdk`
with `platform-tools` (adb), `platforms;android-35` and `build-tools;34.0.0`.
The versions are `ARG`s at the top of the Dockerfile. Building the image
accepts the Android SDK licence agreements (`sdkmanager --licenses`).

Gradle itself is not in the image: `./gradlew` downloads the version pinned in
`gradle/wrapper/gradle-wrapper.properties` into the `gradle-cache` volume.
post-create warms it up once.

There is no emulator. The app needs real NFC hardware and a tag, and the
emulator has neither, so testing happens on a real phone.

## First run

1. Reopen in container.
2. In the container terminal:

   ```
   make test
   make debug
   ```

3. Connect the phone (next section), then `make run-debug`.
4. `claude`: browser OAuth once per devcontainer. After that it auto-starts.

## Reaching the device

**Over the network (default).** Stock Android 11+: Settings → Developer options
→ *Wireless debugging*. Pair once with the code it shows, then connect to the
port on the main screen (it differs from the pairing port):

```
adb pair <ip>:<pairing-port>
make connect DEVICE_IP=<ip> ADB_PORT=<port>
```

On LineageOS, *ADB over network* uses the fixed port 5555, so
`make connect DEVICE_IP=<ip>` is enough. Export `SEEDTAG_DEVICE_IP` on the host
to preset the address. adb's key pair lives in the `android-adb` volume, so
the authorisation survives rebuilds.

**Over USB.** Uncomment the `runArgs` line in `devcontainer.json`, rebuild,
and run `adb kill-server` on the host so the host's adb releases the device.

## Volumes

| Volume                            | Mount             | Holds                                |
|-----------------------------------|-------------------|--------------------------------------|
| `claude-config-${devcontainerId}` | `~/.claude`       | Claude Code auth, settings, sessions |
| `android-keys`                    | `~/.android-keys` | APK signing keys                     |
| `android-adb`                     | `~/.android`      | adb key pair (device authorisation)  |
| `gradle-cache`                    | `~/.gradle`       | Gradle distribution and dependencies |

`android-*` and `gradle-cache` use fixed names on purpose. The signing keys and
the adb identity are yours, so every Android project of yours shares them, and
the Gradle cache only holds public artifacts. Claude's volume is
`${devcontainerId}`-scoped, so sessions stay per project.

## Claude Code

- **Auto-start**: `.vscode/tasks.json` has a `folderOpen` task running
  `claude --continue`, so opening the project resumes the last session for
  the workspace folder. `--continue` exits 1 with no prior session, hence the
  `|| claude` fallback. Drop `runOptions` from the task to make it manual.
  This has to be a task, not `postStartCommand`: lifecycle commands run
  without a TTY and the Claude Code interface needs one.
- **Updates** run on three tracks: the built-in background updater, `claude
  update` in `postStartCommand` on every container start, and a fresh install on
  rebuild. `CLAUDE_CHANNEL` (`latest` or `stable`) sets the auto-update channel.
  `claude doctor` shows the last update attempt. Don't set `DISABLE_AUTOUPDATER`.
- **Permissions** are in `.claude/settings.json`: `Bash(*)` allowed, with the
  signing keys, `.env` and `git push` denied. The container is the real boundary.

## Troubleshooting

- **`adb devices` shows `unauthorized`**: the prompt on the device was missed
  or declined. Toggle debugging off and on, reconnect, accept it.
- **`make connect` times out**: the container reaches the LAN through Docker's
  default bridge, so the device must be reachable from the host too
  (`ping <ip>` there). Guest/isolated Wi-Fi networks block it.
- **`INSTALL_FAILED_UPDATE_INCOMPATIBLE`**: the APK is signed with a different
  key than the installed app (debug vs release, or another key). `make uninstall`
  first.
- **Gradle can't download anything**: the container needs internet access for
  `services.gradle.org`, `dl.google.com`, `maven.google.com` and Maven Central.
  The app has no network access; the build does.
- **Kotlin extension shows red everywhere**: it needs one successful Gradle sync.
  Run `make test` once, then reload the window.
- **Different SDK package versions**: change the `ARG`s in the Dockerfile and
  rebuild, or install extra packages in a running container with `sdkmanager`.
