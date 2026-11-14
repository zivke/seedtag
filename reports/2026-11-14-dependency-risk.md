# Dependencies: who could steal the seed or the password

- **Date:** 2026-11-14
- **Question:** which dependencies, if poisoned, could steal the seed phrase or the password from the current
  app, and how.
- **Examined:** Gradle's resolved `:app` release runtime classpath (offline), `gradle/libs.versions.toml`,
  `gradle/verification-metadata.xml` (362 components), `.devcontainer/Dockerfile` and `devcontainer.json`,
  `.github/workflows/build.yml`, `tools/requirements-ci.txt`, `tools/seedtag.py`'s imports, and the components in
  the manifest of the unsigned release APK built from `a23c13e` (`aapt2 dump xmltree`).
- **Checked by running:** `python3 -I -S tools/seedtag.py verify` on a throwaway test vector
  (`seedtag-v1-vectors.tsv`, row 2) uses the built-in Argon2id and AES-GCM and gives the vector's fingerprint, in
  49 s.
- **Not examined:** the libraries' code itself, Android, the phone's firmware.

## 1. The basic fact

A library in the APK runs in the app's own process, with the app's permissions and memory. Android puts no wall
between them. So every library inside the APK, and every tool that can change what goes into it, could read the
seed words and the password if it were poisoned. Zeroing buffers and `FLAG_SECURE` don't help against code running
in the same process.

What limits a thief is that SeedTag has no `INTERNET` permission, so stolen data needs another way out (section 4).

## 2. Code inside the APK (83 libraries)

| Dependency | Version | What it touches | How it could steal |
|---|---|---|---|
| **Bouncy Castle** `bcprov-jdk18on` | 1.86 | Argon2id: gets the password bytes and salt, returns the AES key | Copy the password. Or, quieter: return a weak or predictable key, so the tag looks encrypted but anyone holding it can open it. |
| **Jetpack Compose** (ui, foundation, runtime, animation, material3, material-icons, ripple) | 1.7.6, material3 1.3.1 | Draws every word and every password character, receives every touch on both keyboards | A complete keylogger and screen reader, whatever the app zeroes afterwards. |
| **Kotlin stdlib** | 2.0.21 | Every string, list and array operation, including on the wordlist's `String`s | Sees the seed words. |
| **kotlinx-coroutines** | 1.7.3 | Runs Argon2 on `Dispatchers.Default`, with the password in the code it runs | Sees the password and the entropy. |
| **activity, lifecycle, savedstate, core** | 1.9.3, 2.8.7, 1.2.1, 1.13.1 | Host the view model that holds every secret; saved state goes to the system | Read the view model, or put secrets in the saved state Android keeps. |
| **startup, emoji2, profileinstaller** | 1.1.1, 1.3.0, 1.3.1 | **Run without being called.** The manifest has `androidx.startup.InitializationProvider`, which starts emoji2 at launch, and an exported `ProfileInstallReceiver`. | The same access, from the moment the app starts. emoji2 already queries Google Play services' font provider: a way out that needs no permission. |
| autofill, collection, arch-core, annotation, tracing, versionedparcelable, concurrent-futures, graphics-path, interpolator, customview-poolingcontainer, guava `listenablefuture` (empty), JetBrains `annotations` | various | Small utilities inside the app | The same power if poisoned. Poisoned Compose plus autofill could hand values to a third-party autofill service, which may have network access. |
| ui-tooling (debug builds only), JUnit (tests only) | | Not in the release APK | None for the released app. |

## 3. What builds the APK

None of these hold secrets, but each can put code into the APK, which then has the powers of section 2:

- **Gradle and its plugins:**
  - Gradle 8.11.1 and its wrapper jar;
  - the Android Gradle Plugin 8.7.3, with D8, R8 and aapt2;
  - Kotlin 2.0.21 and its Compose compiler plugin;
  - about 280 more build-time artifacts. `verification-metadata.xml` pins 362 artifacts in all, among them netty,
    grpc, guava, Apache libraries and protobuf.
- **The toolchain:**
  - the Ubuntu base image, pinned by digest;
  - **OpenJDK 21 from apt, at whatever version the archive has**;
  - the Android command-line tools, pinned by SHA-256;
  - the SDK platform and build-tools, checked by sdkmanager but not pinned by the project.
- **The dev container:** the github-cli feature (tag `:1`, not a digest) and Claude Code. They can't see secrets,
  but they can change the source; only the review at release time catches that.
- **CI:** `actions/checkout` and `actions/upload-artifact`, pinned by commit SHA. A poisoned one could swap the APK,
  but the SHA-256 compared with the container's build would show it.
- **On the host:**
  - `adb` could install a different APK on the phone;
  - `apksigner` and its JDK see the keystore password. A stolen signing key exposes no seed by itself, but it can
    sign a malicious update.

## 4. Ways out without network access

Without `INTERNET` the easy way is closed. These remain:

1. **The tag itself.** An extra NDEF record, or encryption weakened as in the Bouncy Castle row. Whoever gets the
   tag later gets the seed.
2. **Other apps.**
   - An intent that opens a browser with the data in a URL. This is visible, and airplane mode stops the browser
     fetching it.
   - A broadcast or intent to a colluding app already on the phone.
   - Data packed into a query to another app's content provider, as emoji2's font request to Play services already
     is.
3. **Autofill, accessibility, clipboard.** Clearing the window's accessibility-sensitive flag, filling values for an
   autofill service, or copying to the clipboard.
4. **Logcat**, which any computer with adb access to the phone can read.
5. **Turning off `FLAG_SECURE`**, so screenshots and the recent-apps thumbnail show the words.
6. **The app's private storage**, for later collection.

## 5. What the current defenses cover

| Defense | Stops | Doesn't stop |
|---|---|---|
| Checksums pinned everywhere (dependencies, wrapper, distribution, base image, command-line tools) | An artifact changed after it was pinned, or swapped in transit | An artifact already poisoned when it was pinned, or a poisoned new version adopted in an upgrade: `make deps-metadata` records whatever it downloads |
| Reproducible build, compared with CI | A poisoned dev container (tampered cache, changed tools) | A poisoned upstream artifact: both builds use the same one |
| `check-apk.sh` | New permissions, `INTERNET`, new components, a debuggable or backup-allowing APK | New code inside existing classes; intents and provider queries need no permission |
| No `INTERNET` permission | Direct upload | The ways out in section 4 |
| The release review (`release-tag.sh`) | Changes to the source and build files | Code hidden inside a library's binary |

## 6. Verdict

- **Most able to steal:**
  - Compose, which sees all input and output;
  - Bouncy Castle, which sees the password and controls the key;
  - Kotlin stdlib and coroutines;
  - the build tools: AGP, R8 and the Kotlin compiler, which can rewrite anything.
- **The same power, less obvious:** any androidx library in the APK, especially the three that start themselves.
- **Little or no risk to the released app:** JUnit, ui-tooling, the Python packages CI tests with, the CI actions.
- **`seedtag.py`:** it needs only Python's standard library. But a poisoned `cryptography` or `argon2-cffi` on the
  recovery computer sees the password and the seed directly, and a computer is usually online.

## 7. What reduces the risk

1. **Use the phone offline** (airplane mode, restart before and after, or a spare phone that's never online). That
   cuts the ways out that go through other apps' network access at the time of use.
2. **Check a new tag with independent code.** Run `python3 -I -S seedtag.py verify <BASE64>` on another, offline
   computer: `-I -S` keeps installed packages out, so only `seedtag.py`'s own Argon2id and AES-GCM run. A tag whose
   key a poisoned Bouncy Castle weakened doesn't open there. Read the tag with another NFC app too, to see that it
   holds one Text record and nothing else.
3. **Recover with `seedtag.py` alone**, offline, the same way (`python3 -I -S`), if you can wait about a minute per
   password tried.
4. **Treat dependency upgrades as security events:** read the `verification-metadata.xml` diff in the release review,
   and upgrade rarely.
5. **Fewer dependencies:** nearly all the androidx libraries come with Compose; a smaller UI toolkit would mean less
   code inside the app.

Points 2 and 3 are in the README ("Using it safely" and "Recovering on a computer") since this report.
