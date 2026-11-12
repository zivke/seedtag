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

## What the container can reach on the host

VS Code forwards three things from the host into the container, so anything
running here can use them while the window is open:

- **Git credentials** (a credential helper in `/etc/gitconfig` and
  `~/.gitconfig`): pushing to GitHub as you. That's also how VS Code's
  Source Control push works in a container window: it runs `git push` here.
- **The SSH agent** (`SSH_AUTH_SOCK`): every key loaded in it can be used,
  though not read, to log in or to sign.
- **The GPG agent** (`~/.gnupg/S.gpg-agent`): the same for GPG keys.

And the project folder is mounted read-write, `.git/` included: hooks and
config written here run on the host if git runs in that folder there.

Pushing `main` from VS Code is fine. The release doesn't rely on the
container being unable to push; it relies on these (README, "Signing a
release"):

1. **Tag and sign in a separate clone on the host** (`~/seedtag-release`),
   never opened in a container, after reviewing the diff since the last
   release there. Never run git in the mounted folder from a host terminal.
   Don't push tags from VS Code (leave `git.followTagsWhenSync` off).
2. **Keep the release-signing key out of the agents**: a key file with a
   passphrase that's never `ssh-add`ed (or a hardware key). Then the
   container can't make a tag that `git verify-tag` accepts, and
   `sign-release.sh` won't sign what CI built from a tag it pushed.
3. For other keys in the agent (your GitHub login key), add them with
   `ssh-add -c` so each use needs a confirmation on the host, or don't load
   them while the container runs. Unsetting `SSH_AUTH_SOCK` here isn't
   enough: the forwarded socket stays in `/tmp`.

To take push access away completely, set
`"dev.containers.gitCredentialHelperConfigLocation": "none"` in VS Code's
user settings and rebuild the container. VS Code's push button then stops
working here, and `main` is pushed from the release clone instead (it can't
see the container's commits, so bring them over with a bundle: `git bundle
create seedtag.bundle origin/main..main` here, then `git fetch
<path>/seedtag.bundle main:incoming` and `git push origin incoming:main` in the
release clone, without checking `incoming` out). A tag ruleset on
GitHub (Settings › Rules) that restricts `v*` tags only helps once the
container has no credentials, since otherwise it acts as you.

## What is in the image

Ubuntu 24.04 (`mcr.microsoft.com/devcontainers/base`), OpenJDK 21, Python 3
with `cryptography`, `argon2-cffi` and PyYAML, and an Android SDK in `/opt/android-sdk`
with `platform-tools` (adb), `platforms;android-35` and `build-tools;34.0.0`.
The versions are `ARG`s at the top of the Dockerfile. Building the image
accepts the Android SDK license agreements (`sdkmanager --licenses`).

The base image is pinned by digest and the command-line tools zip by its
SHA-256 (from the download table on developer.android.com/studio). When
changing either, update the digest or checksum next to it; the Dockerfile says
how to look up the digest.

Gradle itself is not in the image: `./gradlew` downloads the version pinned in
`gradle/wrapper/gradle-wrapper.properties` into the `gradle-cache` volume, and
checks it against `distributionSha256Sum` there.
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
the authorization survives rebuilds.

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

The shared adb identity means anything that runs in *any* of those containers (build scripts, dependencies, an AI
agent) can reach every phone that authorized it: install or replace apps, change settings, turn on accessibility
services. That's fine for a test phone, and the reason a phone that holds real seeds must never be authorized here
(see *Using it safely* in the main README). For a separate identity per project, use
`source=android-adb-${devcontainerId}` in `devcontainer.json` and authorize the phone again.

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
