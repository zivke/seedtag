# Devcontainer

VS Code devcontainer for SeedTag: JDK, Android SDK, Gradle cache, adb, Python
for `seedtag.py` and Claude Code, none of it installed on the host.

## Release signing happens outside the container

The release signing key never enters this container: anything that runs here
(build scripts, dependencies, Claude Code) could otherwise read it and sign a
fake update. `make build` produces an **unsigned** `seedtag-unsigned.apk`; you
sign it on the machine that holds the key with `tools/sign-release.sh` (see
the README, "Signing a release"). `make debug` and `make build-test` use
Android's debug key, which lives in the `android-adb` volume.

Debug-key and release-key builds can't be installed over each other, so
switching between them on the device needs `make uninstall` first.

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
| `android-adb`                     | `~/.android`      | adb key pair, Android debug key      |
| `gradle-cache`                    | `~/.gradle`       | Gradle distribution and dependencies |

`android-adb` and `gradle-cache` use fixed names on purpose. The adb identity is
yours, so every Android project of yours shares it, and the Gradle cache only
holds public artifacts. There's deliberately no volume with signing keys. Claude's volume is
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
- **Permissions** are in `.claude/settings.json`: `Bash(*)` allowed, with
  key files, `.env` and `git push` denied. The container is the real boundary,
  which is why the release key isn't in it.

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
