# Dependencies: what can be removed or replaced

- **Date:** 2026-11-15
- **Question:** which dependencies could and should be removed or replaced, to shrink the code that can see
  the seed and the password, and how. Builds on `reports/2026-11-14-dependency-risk.md`; doesn't repeat it.
- **Examined:** the unsigned release APK built from `8c287c8` (`make build`, offline): its dex through
  `apkanalyzer dex packages` with R8's `mapping.txt`, its merged manifest (`aapt2 dump xmltree`) and its file
  list; Gradle's resolved `:app` release runtime classpath; the app's imports; `SeedBox.kt`; the built-in
  Argon2id in `tools/seedtag.py`; `.devcontainer/`, `.github/workflows/build.yml`, `tools/check-apk.sh`,
  `tools/requirements-ci.txt`.
- **Checked by running** (in a throwaway worktree, nothing merged; the whole diff is in the appendix):
  - five experiments, each a clean `make build` that passed `check-apk.sh`: (A) manifest removals, (B) Gradle
    excludes, (C) our own Argon2id in Kotlin with Bouncy Castle as a test-only dependency, (D) dropping the
    unused `ui-tooling` pair, (E) plain `lifecycle-viewmodel` instead of `lifecycle-viewmodel-compose`;
  - `make test` on the result: 47 Kotlin tests (3 new) and 22 Python tests pass, 2 Python tests skipped as usual;
  - the final tree built twice: the same SHA-256 both times (experiment C's tree, `ca4c3a89…`);
  - `make debug` on the result passes `check-apk.sh --debug`.
- **Not examined:** anything on a phone. No experiment APK was installed or started. The libraries' source was
  not read, except the names and sizes of what survives R8. Android's own behavior (content capture, baseline
  profiles) is stated from memory where marked, not verified.

## 1. What is in the release APK

Baseline, `8c287c8`: APK 1,060,305 bytes, 78 files, one `classes.dex` of 1,777,188 bytes with 2,275 classes and
10,168 methods defined (12,365 referenced). The classpath has 86 resolved artifacts by my count (the earlier
report counted 83 libraries; the difference is BOM and platform-variant entries).

| Code | Classes | Methods | Dex bytes | Share |
|---|--:|--:|--:|--:|
| Compose (ui, runtime, foundation, animation, text, graphics, unit, geometry) | 1,277 | 6,282 | 842,448 | 57.8% |
| **SeedTag app** | 146 | 549 | 146,556 | 10.1% |
| kotlinx-coroutines | 203 | 788 | 101,112 | 6.9% |
| Compose material3 + material-ripple + material-icons | 163 | 459 | 104,570 | 7.2% |
| Kotlin stdlib | 141 | 607 | 57,081 | 3.9% |
| androidx.core | 94 | 438 | 49,220 | 3.4% |
| androidx.collection | 33 | 262 | 43,983 | 3.0% |
| lifecycle + activity + savedstate + arch-core | 112 | 335 | 45,767 | 3.1% |
| profileinstaller + emoji2 + startup + concurrent-futures | 54 | 171 | 27,230 | 1.9% |
| **Bouncy Castle** | 16 | 59 | 12,109 | 0.8% |
| **SeedTag core** | 10 | 64 | 9,451 | 0.6% |
| the rest (poolingcontainer, versionedparcelable, interpolator, graphics-path, autofill glue, synthetic) | 26 | 154 | 17,848 | 1.2% |

Reading it:

- SeedTag's own code is 10.7% of the dex. Compose with material3 is 65%. Every other library is small.
- R8 merges and inlines classes, so the per-library class counts are approximate (a merged class is counted
  under the name that survived). The totals are exact. Shares are of the 1,457,375 bytes that `apkanalyzer`
  attributes to classes; the rest of the dex file is shared tables.
- Bouncy Castle: of a 7.2 MB jar, 16 classes survive. Five are Argon2 and BLAKE2b
  (`Argon2BytesGenerator` and its two inner classes, `Argon2Parameters`, `Blake2bDigest`). The other eleven are
  dragged in by `CryptoServicesRegistrar`'s static initializer: `DHParameters`, `DSAParameters`,
  `DSAValidationParameters`, `Hex`, `HexEncoder`, `DecoderException`, `Properties`, `CryptoServicesPermission`.
- Not code, but in the APK because of Bouncy Castle: `org/bouncycastle/x509/CertPathReviewerMessages.properties`
  and its `_de` twin (96 KB uncompressed), three OSGi manifests, `META-INF/LICENSE.md`.
- Native code: `libandroidx.graphics.path.so` for four ABIs (7 to 11 KB each), from `androidx.graphics:graphics-path`,
  loaded by Compose on Android 13 and older when a path is iterated. The app has no other native code.
- Two `META-INF/services` files (coroutines' main dispatcher and exception handler, found through `ServiceLoader`).

## 2. What runs without being called

The earlier report named the startup provider, emoji2 and the profile installer. The APK shows more:

| Piece | From | When it runs | Seen in |
|---|---|---|---|
| `androidx.startup.InitializationProvider` | startup, merged from emoji2, lifecycle-process, profileinstaller | Before `MainActivity`, at process start. Runs `EmojiCompatInitializer`, `ProcessLifecycleInitializer`, `ProfileInstallerInitializer`. | manifest |
| emoji2's font request | emoji2 | A few seconds after start: looks for a font provider app (Play services) and queries it | 27 classes in the dex |
| `ProfileInstallReceiver`, **exported** | profileinstaller | When something holding `DUMP` (adb) sends one of four broadcasts | manifest |
| `androidx.core.app.CoreComponentFactory` as `appComponentFactory` | androidx.core | Instantiates every component, the activity included | manifest |
| Compose's content-capture manager | compose ui | On Android 10+, while the screen is shown: reports the on-screen text nodes to the platform's content-capture service, if one is active | `AndroidContentCaptureManager` and `ContentCaptureSessionCompat` survive R8 |
| Compose's autofill hook | compose ui | Android 8+: registers an autofill callback for the window. The app has no fillable nodes, so the tree is empty. | `AndroidAutofill`, `AutofillCallback` survive R8 |
| coroutines' `ServiceLoader` lookups | kotlinx-coroutines | First use of `Dispatchers.Main` | `META-INF/services` |

The content-capture row is new and matters more than the others: the Restore screen shows the words as text.
From memory, Android turns content capture off for a window with `FLAG_SECURE`, which SeedTag sets, but it sets
it after `super.onCreate()`, and I could not verify offline at which point Android reads the flag. It costs two
lines to stop depending on that (section 8, item 4).

## 3. Experiments

All numbers are from clean release builds. Each step includes the ones before it.

| Build | APK bytes | Files | Dex bytes | Classes | Methods | Resolved artifacts |
|---|--:|--:|--:|--:|--:|--:|
| Baseline `8c287c8` | 1,060,305 | 78 | 1,777,188 | 2,275 | 10,168 | 86 |
| A: manifest removals | 1,043,277 | 78 | 1,720,384 | 2,198 | 9,914 | 86 |
| B: + Gradle excludes | 1,042,354 | 75 | 1,719,976 | 2,195 | 9,911 | 81 |
| C: + own Argon2id, no Bouncy Castle | 1,009,755 | 69 | 1,708,452 | 2,181 | 9,862 | 80 |
| D: + no `ui-tooling` | 1,009,558 | 68 | 1,708,452 | 2,181 | 9,862 | 78 |
| E: + plain `lifecycle-viewmodel` | 1,009,349 | 67 | 1,708,452 | 2,181 | 9,862 | 76 |

**A. Manifest removals.** `tools:node="remove"` on the startup provider and the profile-installer receiver,
`tools:remove="android:appComponentFactory"` on `<application>`, and `check-apk.sh`'s allowlist cut down to the
one activity.

- The merged manifest then has one component: `MainActivity`.
- R8 does the rest. With nothing calling the initializers, emoji2 goes from 27 classes to 2 stubs,
  profileinstaller from 16 to 1, startup and concurrent-futures to 0. `EmojiCompat` itself is gone, and with it
  the font-provider query: R8 proved Compose's emoji check always answers "not configured".
- Cost: text is drawn without emoji2's replacement glyphs (the app shows no emoji; `✓` and `↗` are ordinary
  characters, but check on the phone). And nothing installs the baseline profile on a sideloaded APK, so the
  first starts may be slightly slower.

**B. Gradle excludes.** `profileinstaller`, `startup-runtime`, `lifecycle-process`, `concurrent-futures` and
`listenablefuture` excluded from every configuration. The dex loses only the last 3 stub classes; the gain is
that five artifacts are no longer inputs to the build at all. If a later androidx version starts calling one of
them from live code, R8 stops the build with a missing-class error, so it fails safe.

**C. Our own Argon2id.** `core/…/Argon2id.kt`, 254 lines with BLAKE2b, one lane, no secret or associated data.
Bouncy Castle moves to `testImplementation` in `:core` and leaves the app.

- Correct on the first run against everything available: the existing `SeedBoxTest` vectors (made by
  `seedtag.py`), and a new `Argon2idTest` comparing with Bouncy Castle over 280 random parameter sets (memory 8
  to 4,099 KiB, 1 to 4 passes, output 4 to 200 bytes), every password length from 0 to 300 bytes, BLAKE2b over
  307 input lengths and 5 digest sizes, and the real parameters (t=3, 64 MiB).
- Speed on the container's JVM at the real parameters, best of 4: Bouncy Castle 148 ms, ours 192 ms. That says
  nothing reliable about ART on a phone.
- In the APK: Bouncy Castle's 16 classes and 11.3 KB of dex go; core grows by one class and 3.9 KB. The
  `.properties` files, OSGi manifests and the `-dontwarn org.bouncycastle.**` rule go too.
- RFC 9106's own Argon2id vector uses four lanes, a secret and associated data, which this code doesn't have.
  It can't be run as is; the comparison with Bouncy Castle covers far more inputs than one vector would.

**D. `ui-tooling`.** The app has no `@Preview`. Dropping `ui-tooling-preview` and the debug-only `ui-tooling`
changes no code in the release APK. It removes six artifacts from what `verification-metadata.xml` has to pin
and the `PreviewActivity` that debug builds carry.

**E. `lifecycle-viewmodel-compose`.** The app imports nothing from it (`viewModels()` is from activity,
`viewModelScope` from lifecycle-viewmodel). Simply deleting the line fails: lifecycle then resolves to 2.8.3,
which isn't in `verification-metadata.xml`, and the build stops at dependency verification, as it should.
Naming `lifecycle-viewmodel` 2.8.7 as the direct dependency instead builds, with an identical dex.

## 4. Each candidate

| Candidate | What the app uses it for | Verdict | Effort | Gain |
|---|---|---|---|---|
| startup provider, emoji2 initializer, profile installer + receiver | Nothing | **Remove** (A, B) | Minutes | Nothing runs before the activity; no exported receiver; no font-provider query; 77 classes gone |
| `CoreComponentFactory` | Nothing | **Remove** (A) | One attribute | One less hook at process start |
| `ui-tooling`, `ui-tooling-preview` | Nothing | **Remove** (D) | Two lines | Six fewer pinned artifacts; no debug `PreviewActivity` |
| `lifecycle-viewmodel-compose` | Nothing | **Swap** (E) | Two lines | Two fewer artifacts |
| Bouncy Castle | Argon2id only | **Replace, on a branch** (C, section 5) | 1 to 2 days with the phone tests | The one library that sees the password and makes the key becomes 254 frozen lines in the repo |
| material3 | `Button` variants, `AlertDialog`, `Scaffold`, `Surface`, `Text`, `SegmentedButton`, `SuggestionChip`, `CircularProgressIndicator`, `MaterialTheme` | Not now | Several days: about 15 widgets rewritten on foundation, every screen rechecked | 161 classes, 7% of the dex, 3 artifacts, all from the publisher of the Compose code that stays |
| material-icons | Five icons | No, unless material3 goes | An hour | 2 classes, 1.7 KB. It comes with material3 anyway. |
| autofill (`androidx.autofill`, Compose's hook) | Nothing | Can't remove (inside compose ui); **switch off** at the view | One line | Nothing offered to an autofill service whatever Compose does |
| activity, lifecycle, savedstate, arch-core, core | `ComponentActivity`, `ViewModel`, `BackHandler`, edge-to-edge, insets | Keep while on Compose | | `setContent` needs them; removing them means removing Compose |
| kotlinx-coroutines | `viewModelScope.launch`, `withContext(Dispatchers.Default)`, one `SharedFlow`, `delay` in the UI | **Keep** | | None: Compose's runtime is built on coroutines, so the 202 classes stay whatever the app does. An executor would only add code. |
| `graphics-path` native library | Nothing directly | Leave | | Excluding the `.so` risks a crash on Android 13 and older, where Compose's path code may load it. Only a phone on such a version can tell. |
| Kotlin stdlib | Everything | Keep | | |

## 5. Bouncy Castle or our own Argon2id

The usual rule is right: don't hand-roll crypto. This case is the exception, for reasons specific to Argon2id
and to this project.

For replacing:

- **Side channels don't distinguish the two.** BLAKE2b and Argon2's mixing are additions, XORs, rotations and
  a multiplication: no secret-indexed tables, no secret-dependent branches. The password-dependent memory
  addressing in the later passes is part of Argon2id and is the same in Bouncy Castle. Bouncy Castle's
  Argon2 is plain Java too, with no hardware help. AES-GCM, where an own implementation would be a real
  side-channel risk, stays in the platform cipher.
- **A wrong KDF can't be subtly wrong.** It is a deterministic function; one flipped bit changes the whole key.
  Tests against an independent implementation catch every error on the inputs tested, and the inputs are
  easy to cover. Four independent implementations already agree through the vector files: Bouncy Castle,
  `seedtag.py`'s built-in, argon2-cffi (the reference C code) and OpenSSL through `cryptography`.
- **It never needs to change.** Format v1 fixes the parameters for good. Bouncy Castle has to be re-trusted at
  every upgrade, and its advisories (for X.509, TLS, PQC code that never reaches the APK) create pressure to
  upgrade. 254 lines read once stay read.
- **It removes the only non-Google, non-JetBrains publisher from the APK**, and the one library the earlier
  report ranked highest: it sees the password and can quietly weaken the key.
- Bouncy Castle stays in the project where it is most useful: as the reference the tests compare against.

Against:

- `CLAUDE.md` says Bouncy Castle is the only crypto library. The rule would have to become "the platform cipher
  for AES-GCM, our own Argon2id checked against Bouncy Castle in tests".
- Our code allocates the 64 MiB as one `LongArray`; Bouncy Castle uses 65,536 small arrays. On a low-memory
  phone the single allocation could fail where the many small ones didn't, or the reverse. Only phones can tell.
- 30% slower on the desktop JVM; unknown on ART. A slow KDF on an old phone is a usability cost.
- A mistake here writes tags that only this app can read. The app decrypts each blob before writing, but with
  the same code, so that check wouldn't see it. The tests and the README's `seedtag.py verify` step would.
- The author of the app becomes the maintainer of a primitive. Small, but permanent.

Verdict: a real improvement, not a step backwards, **if** it ships with the differential tests and after timing
and memory checks on the oldest phone at hand. It is not urgent: after R8, Bouncy Castle is 16 pinned classes.
A middle road, copying Bouncy Castle's five classes into the repo, keeps today's exact code path but brings
about 1,200 lines of Java written for a different context; the 254-line Kotlin version is easier to review.

## 6. Compose or plain Views

What a Views rewrite would give: the APK would contain SeedTag, the Kotlin stdlib and (unless section 5 is done)
Bouncy Castle. About 1,950 library classes and 1.2 MB of dex would go, the resolved artifacts would drop from
86 to 3 or 4, and the Compose compiler plugin would leave the build. Everything in section 2 would disappear,
content capture and autofill included, because the app would decide what each view reports. With section 5 the
only third-party code in the APK would be the Kotlin stdlib.

What it costs: `Ui.kt` is 2,118 lines and about 60 composables, and the view model holds its state in about 20
Compose state objects. All of it would be rewritten: both keyboards, the dialogs with their overlay and accessibility
guards, the blur, the scroll hints, the idle watch, dark theme, insets. Every item of the manual test plan
would be run again on a phone. Three to five weeks of work, and a period in which the UI is newer and less
tested than today's, in an app where a UI mistake (a wrong word shown, a dialog skipped) is itself a security
problem.

What it doesn't change: the views that would replace Compose are Android framework code, in the same process,
from the same publisher. The androidx libraries come from the publisher of the Android Gradle Plugin and R8,
which can rewrite the whole APK anyway. So the rewrite reduces the number of separately uploaded artifacts that
must each be clean, not the number of parties trusted.

Verdict: not now. It is the only step that changes the picture by an order of magnitude, so it is a fair goal
for a 1.0, taken as its own project. It should not be started as a side effect of anything else. The items
below remove what runs on its own for a hundredth of the cost.

## 7. The build side

| Item | Now | Could be | Worth it |
|---|---|---|---|
| github-cli feature | `:1` with `"version": "latest"`. The lock file pins the feature's install script, not the `gh` binary it downloads. | Removed: `gh` is only used on the host (`gh release create`, `gh workflow run`). Or `"version"` set to a fixed release. | Yes, cheap. It isn't in the CI image, so the APK comparison already covers it; this is about what can edit the source. |
| VS Code extensions | Seven, unpinned, auto-updating, with write access to the source | Fewer: the pull-request extension and GitLens aren't needed to build or test | Small; the release review is the real defense |
| Claude Code | Installed by `curl | bash`, updates itself | Nothing practical | Same: caught by the release review |
| OpenJDK 21 | From apt, unpinned (the README says so) | A JDK tarball pinned by SHA-256, as the command-line tools are | Later. It runs the compiler and R8, and both builds take it from the same archive, so the comparison can't catch a bad one. |
| SDK platform and build-tools | Checked by `sdkmanager` against Google's index, not pinned here | `sha256sum -c` in the Dockerfile for `platforms/android-35/android.jar` and `build-tools/34.0.0/core-lambda-stubs.jar`, the two files that are inputs to R8 | Yes, cheap |
| `verification-metadata.xml` | 362 components | Fewer after B, D and E, once re-recorded (`make deps-metadata`, needs network) | Comes with those items |
| CI actions | Two, pinned by commit SHA | | Nothing to do |
| `requirements-ci.txt` | Hash-pinned, test only | | Nothing to do |
| `seedtag.py`'s optional packages | `cryptography` or `argon2-cffi`; neither needed to decrypt | Encrypting without `cryptography` would mean table-based AES in Python for writing: slower and not constant-time | No. Recovery already works with the standard library alone. |
| apt Python packages in the image | `python3-cryptography`, `python3-argon2`, `python3-yaml`, test only | | Nothing to do |

## 8. Ranked recommendations

**Now** (an afternoon together, then one pass on the phone):

1. **Remove the self-starters from the manifest** (experiment A) and cut `check-apk.sh`'s allowlist to
   `MainActivity`. Gain: no library code runs before the activity, no exported receiver, no font-provider
   query, 77 fewer classes, and from then on any component a library adds fails the build.
2. **Exclude the five artifacts** (experiment B). Gain: they stop being build inputs. Cost: an exclude list to
   keep; it fails at build time, not at run time.
3. **Drop `ui-tooling` and `ui-tooling-preview`; name `lifecycle-viewmodel` instead of
   `lifecycle-viewmodel-compose`** (D, E). Gain: eight fewer artifacts to pin, no `PreviewActivity` in debug
   builds, so `check-apk.sh --debug` needs no extra allowance. No change to the release dex.
4. **Opt out of content capture and autofill explicitly** in `MainActivity.onCreate` and `GuardDialogWindow()`:
   `decorView.importantForContentCapture = IMPORTANT_FOR_CONTENT_CAPTURE_NO_EXCLUDE_DESCENDANTS` (Android 11+)
   and `decorView.importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS` (Android 8+), and move
   `FLAG_SECURE` before `super.onCreate()`. Not a dependency removal, but it closes the one path in section 2
   where a library hands displayed text to another process by design. Not tried here.
5. **Dev container:** remove the github-cli feature (or fix its version); add the two SDK checksums to the
   Dockerfile.

After 1 to 3, run `make deps-metadata` (network) so the metadata loses the unused entries, and put its comment back.

**Later** (worth doing, each on its own branch):

6. **Our own Argon2id, Bouncy Castle test-only** (experiment C, section 5). Before merging: Argon2 time and a
   full back up, verify and restore on the oldest and the lowest-memory phone available; a tag written by the
   new build opened with `python3 -I -S tools/seedtag.py verify`; the `CLAUDE.md` rule, README and changelog
   updated. The blob format doesn't change, so no new version byte and no new vector rows are needed.
7. **Pin the JDK by SHA-256.**

**Only with other UI work:**

8. material3 replaced by the app's own widgets on foundation, own vector icons with it.

**Not to do:**

- Replace coroutines with threads: Compose keeps them in the APK regardless.
- Replace material-icons alone: 1.7 KB.
- Exclude the `graphics-path` native library without a phone on Android 13 or older to prove it is never loaded.
- Rewrite the UI in Views now (section 6).
- Make `seedtag.py` encrypt without `cryptography`.

## 9. What needs a phone

Nothing here was run on a device. Before any of it is released:

- A, B: the app starts and every screen draws (text, `✓`, `↗`, icons) on the oldest supported Android (8.0) and
  a current one; cold-start time is acceptable without the profile installer.
- Item 4: `adb shell dumpsys content_capture` and `dumpsys autofill` show no session for SeedTag, before and
  after the change, to learn whether today's build is already covered by `FLAG_SECURE`.
- C: Argon2 time and peak memory on a low-end phone, compared with the current build; no `OutOfMemoryError`
  on the 64 MiB allocation, also on a second run straight after the first; tags written by the old build
  open with the new one and the reverse.

## Status since the report

| Item | Status |
|---|---|
| 1. Self-starters out of the manifest | Done on branch `dependency-trim`: the startup provider removed, `appComponentFactory` removed, and `check-apk.sh` now allows only `MainActivity` and fails an APK with any `appComponentFactory`. The profile-installer receiver goes with item 2 (no manifest entry needed). Lint's `MissingClass` on the removed provider is suppressed. Needs the phone (section 9). |
| 2. Five artifacts excluded | Done on `dependency-trim`. |
| 3. `ui-tooling` pair dropped, `lifecycle-viewmodel` named | Done on `dependency-trim`; the debug `PreviewActivity` allowance in `check-apk.sh` went with it. `make deps-metadata` was run from an empty component list, since Gradle keeps stale entries: 22 components (35 files) left `verification-metadata.xml`, none added or changed; `check-deps.py` matched all 611 files with their repositories. |
| 4. Content capture and autofill off, `FLAG_SECURE` first | Done on `dependency-trim`, for the activity and every dialog (`excludeFromCaptureAndAutofill()`). Needs the `dumpsys` check on the phone. |
| 5. Dev container | Done on `dependency-trim`: the github-cli feature and its lock file removed; `android.jar` and `core-lambda-stubs.jar` checked by SHA-256 in the Dockerfile (hashed from this container's install; the CI image build is the independent check). |
| 6. Own Argon2id | Open: needs the owner's decision on the `CLAUDE.md` crypto rule. |
| 7. JDK pinned | Open. |

Release APK on `dependency-trim` (items 1–3): 1,041,952 bytes, 2,195 classes; reproducible (two clean builds,
SHA-256 `3a7202f7…`). R8's mapping shows `EmojiCompat`, the startup and profile-installer code gone; Compose's
content-capture and autofill classes remain, as expected (item 4 switches them off at the view).

## Appendix: the diff of all five experiments

Against `8c287c8`. Apply with `git apply`. The manifest's `<receiver … tools:node="remove">` becomes redundant
once profileinstaller is excluded (the merger warns that there is nothing to remove); keep one or the other.

```diff
diff --git a/app/build.gradle.kts b/app/build.gradle.kts
index 7a1a7a8..e20929a 100644
--- a/app/build.gradle.kts
+++ b/app/build.gradle.kts
@@ -38,10 +38,6 @@ android {
 
     buildFeatures { compose = true }
 
-    packaging {
-        resources { excludes += setOf("META-INF/versions/9/OSGI-INF/MANIFEST.MF") }
-    }
-
     // No Google dependency-metadata blob in the APK (keeps builds reproducible / F-Droid friendly)
     dependenciesInfo {
         includeInApk = false
@@ -49,16 +45,22 @@ android {
     }
 }
 
+// Libraries that only exist to start themselves (see the manifest): not on the classpath at all.
+configurations.configureEach {
+    exclude(group = "androidx.profileinstaller", module = "profileinstaller")
+    exclude(group = "androidx.startup", module = "startup-runtime")
+    exclude(group = "androidx.lifecycle", module = "lifecycle-process")
+    exclude(group = "androidx.concurrent", module = "concurrent-futures")
+    exclude(group = "com.google.guava", module = "listenablefuture")
+}
+
 dependencies {
     implementation(project(":core"))
-    implementation(libs.bouncycastle)
 
     implementation(platform(libs.compose.bom))
     implementation(libs.compose.ui)
     implementation(libs.compose.foundation)
     implementation(libs.compose.material3)
-    implementation(libs.compose.ui.tooling.preview)
-    debugImplementation(libs.compose.ui.tooling)
     implementation(libs.activity.compose)
-    implementation(libs.lifecycle.viewmodel.compose)
+    implementation(libs.lifecycle.viewmodel)
 }
diff --git a/app/proguard-rules.pro b/app/proguard-rules.pro
index 7b71640..74a1024 100644
--- a/app/proguard-rules.pro
+++ b/app/proguard-rules.pro
@@ -1,4 +1,2 @@
-# Bouncy Castle: only the Argon2 generator is used, referenced directly (no reflection).
--dontwarn org.bouncycastle.**
 # Keep the BIP39 wordlist resource lookup stable.
 -keep class rs.zivke.seedtag.core.Bip39 { *; }
diff --git a/app/src/main/AndroidManifest.xml b/app/src/main/AndroidManifest.xml
index c3feefa..d17723d 100644
--- a/app/src/main/AndroidManifest.xml
+++ b/app/src/main/AndroidManifest.xml
@@ -27,8 +27,20 @@
         android:label="@string/app_name"
         android:supportsRtl="true"
         android:theme="@style/Theme.SeedTag"
+        tools:remove="android:appComponentFactory"
         tools:targetApi="31">
 
+        <!-- Nothing starts by itself: no androidx.startup provider (it ran emoji2's font-provider
+             lookup, the profile installer and ProcessLifecycleOwner at launch) and no exported
+             profile-installer receiver. -->
+        <provider
+            android:name="androidx.startup.InitializationProvider"
+            android:authorities="${applicationId}.androidx-startup"
+            tools:node="remove" />
+        <receiver
+            android:name="androidx.profileinstaller.ProfileInstallReceiver"
+            tools:node="remove" />
+
         <!-- taskAffinity "": no other app can put its activities into SeedTag's task
              (task hijacking / StrandHogg, which Android 8 and 9 are open to). -->
         <activity
diff --git a/core/build.gradle.kts b/core/build.gradle.kts
index a0f6908..bbf0d3b 100644
--- a/core/build.gradle.kts
+++ b/core/build.gradle.kts
@@ -15,6 +15,7 @@ kotlin {
 }
 
 dependencies {
-    implementation(libs.bouncycastle)
+    // Test-only: the reference our own Argon2id is compared with. Not in the APK.
+    testImplementation(libs.bouncycastle)
     testImplementation(libs.junit)
 }
diff --git a/core/src/main/kotlin/rs/zivke/seedtag/core/Argon2id.kt b/core/src/main/kotlin/rs/zivke/seedtag/core/Argon2id.kt
new file mode 100644
index 0000000..549eb93
--- /dev/null
+++ b/core/src/main/kotlin/rs/zivke/seedtag/core/Argon2id.kt
@@ -0,0 +1,254 @@
+// SPDX-License-Identifier: GPL-3.0-or-later
+// Copyright (C) 2026 zivke
+package rs.zivke.seedtag.core
+
+/**
+ * Argon2id v1.3 (RFC 9106) with one lane, no secret and no associated data: all blob format v1 needs.
+ * BLAKE2b (RFC 7693) is here too, because neither the JDK nor Android has it.
+ *
+ * Only additions, XORs, rotations and one multiplication of public-width words: no tables indexed by
+ * secrets and no branches on them. The memory addressing of the second half of the first pass and of
+ * the later passes depends on the password; that is Argon2id itself, the same in every implementation.
+ *
+ * Checked against Bouncy Castle (a test-only dependency) over random parameters, and against
+ * seedtag.py through the shared vector files.
+ */
+internal object Argon2id {
+    private const val KIND_ID = 2
+    private const val VERSION = 0x13
+    private const val WORDS = 128 // one block: 1024 bytes as 128 little-endian 64-bit words
+    private const val M32 = 0xFFFFFFFFL
+
+    fun hash(password: ByteArray, salt: ByteArray, passes: Int, memoryKib: Int, outLen: Int): ByteArray {
+        require(passes >= 1 && memoryKib >= 8 && outLen >= 4)
+        val q = 4 * (memoryKib / 4) // blocks in the one lane
+        val seg = q / 4
+        val mem = LongArray(q * WORDS)
+        val r = LongArray(WORDS)
+        val z = LongArray(WORDS)
+        val addresses = LongArray(WORDS)
+        val h0 = initialHash(password, salt, passes, memoryKib, outLen)
+        val seed = ByteArray(72)
+        val block = ByteArray(8 * WORDS)
+        try {
+            h0.copyInto(seed)
+            for (i in 0..1) {
+                seed[64] = i.toByte() // LE32(i) LE32(lane 0); bytes 65..71 stay zero
+                val b = longHash(seed, 8 * WORDS)
+                for (w in 0 until WORDS) mem[i * WORDS + w] = le64(b, 8 * w)
+                b.fill(0)
+            }
+            for (pass in 0 until passes) {
+                for (s in 0 until 4) {
+                    val independent = pass == 0 && s < 2 // Argon2id: data-independent addressing here
+                    var counter = 0L
+                    var haveAddresses = false
+                    for (k in (if (pass == 0 && s == 0) 2 else 0) until seg) {
+                        val j = s * seg + k
+                        val prev = (if (j == 0) q - 1 else j - 1) * WORDS
+                        val j1 = if (independent) {
+                            if (k % WORDS == 0 || !haveAddresses) {
+                                counter++
+                                r.fill(0)
+                                r[0] = pass.toLong(); r[2] = s.toLong(); r[3] = q.toLong()
+                                r[4] = passes.toLong(); r[5] = KIND_ID.toLong(); r[6] = counter
+                                // addresses = G(0, G(0, input))
+                                r.copyInto(addresses)
+                                permute(addresses)
+                                for (w in 0 until WORDS) { addresses[w] = addresses[w] xor r[w]; r[w] = addresses[w] }
+                                permute(addresses)
+                                for (w in 0 until WORDS) addresses[w] = addresses[w] xor r[w]
+                                haveAddresses = true
+                            }
+                            addresses[k % WORDS] and M32
+                        } else {
+                            mem[prev] and M32
+                        }
+                        // Which earlier block to mix in (RFC 9106, 3.4.2), the lane always being this one.
+                        val area: Long
+                        val origin: Long
+                        if (pass == 0) {
+                            area = j - 1L; origin = 0L
+                        } else {
+                            area = q - seg + k - 1L; origin = ((s + 1L) * seg) % q
+                        }
+                        val x = (j1 * j1) ushr 32
+                        val ref = ((origin + area - 1 - ((area * x) ushr 32)) % q).toInt() * WORDS
+                        val out = j * WORDS
+                        for (w in 0 until WORDS) { r[w] = mem[prev + w] xor mem[ref + w]; z[w] = r[w] }
+                        permute(z)
+                        if (pass == 0) {
+                            for (w in 0 until WORDS) mem[out + w] = z[w] xor r[w]
+                        } else {
+                            for (w in 0 until WORDS) mem[out + w] = mem[out + w] xor z[w] xor r[w]
+                        }
+                    }
+                }
+            }
+            val last = (q - 1) * WORDS
+            for (w in 0 until WORDS) putLe64(block, 8 * w, mem[last + w])
+            return longHash(block, outLen)
+        } finally {
+            mem.fill(0); r.fill(0); z.fill(0); addresses.fill(0)
+            h0.fill(0); seed.fill(0); block.fill(0)
+        }
+    }
+
+    private fun initialHash(password: ByteArray, salt: ByteArray, passes: Int, memoryKib: Int, outLen: Int): ByteArray {
+        val input = ByteArray(40 + password.size + salt.size)
+        try {
+            var n = 0
+            for (v in intArrayOf(1, outLen, memoryKib, passes, VERSION, KIND_ID, password.size)) { putLe32(input, n, v); n += 4 }
+            password.copyInto(input, n); n += password.size
+            putLe32(input, n, salt.size); n += 4
+            salt.copyInto(input, n)
+            // The last eight bytes stay zero: no secret, no associated data.
+            return Blake2b.hash(input, 64)
+        } finally {
+            input.fill(0)
+        }
+    }
+
+    /** H' (RFC 9106, 3.3): a hash of any length, built from BLAKE2b. */
+    private fun longHash(data: ByteArray, length: Int): ByteArray {
+        val first = ByteArray(4 + data.size)
+        try {
+            putLe32(first, 0, length)
+            data.copyInto(first, 4)
+            if (length <= 64) return Blake2b.hash(first, length)
+            val out = ByteArray(length)
+            val rounds = (length + 31) / 32 - 2
+            var v = Blake2b.hash(first, 64)
+            v.copyInto(out, 0, 0, 32)
+            for (i in 1 until rounds) {
+                val next = Blake2b.hash(v, 64)
+                v.fill(0)
+                v = next
+                v.copyInto(out, 32 * i, 0, 32)
+            }
+            val tail = Blake2b.hash(v, length - 32 * rounds)
+            tail.copyInto(out, 32 * rounds)
+            v.fill(0); tail.fill(0)
+            return out
+        } finally {
+            first.fill(0)
+        }
+    }
+
+    /** The permutation P (RFC 9106, 3.6) over a block: the BLAKE2b round on its 8 rows, then its 8 columns. */
+    private fun permute(v: LongArray) {
+        for (i in 0 until 8) {
+            val b = 16 * i
+            round(v, b, b + 1, b + 2, b + 3, b + 4, b + 5, b + 6, b + 7,
+                b + 8, b + 9, b + 10, b + 11, b + 12, b + 13, b + 14, b + 15)
+        }
+        for (i in 0 until 8) {
+            val b = 2 * i
+            round(v, b, b + 1, b + 16, b + 17, b + 32, b + 33, b + 48, b + 49,
+                b + 64, b + 65, b + 80, b + 81, b + 96, b + 97, b + 112, b + 113)
+        }
+    }
+
+    private fun round(
+        v: LongArray, i0: Int, i1: Int, i2: Int, i3: Int, i4: Int, i5: Int, i6: Int, i7: Int,
+        i8: Int, i9: Int, i10: Int, i11: Int, i12: Int, i13: Int, i14: Int, i15: Int,
+    ) {
+        gb(v, i0, i4, i8, i12); gb(v, i1, i5, i9, i13); gb(v, i2, i6, i10, i14); gb(v, i3, i7, i11, i15)
+        gb(v, i0, i5, i10, i15); gb(v, i1, i6, i11, i12); gb(v, i2, i7, i8, i13); gb(v, i3, i4, i9, i14)
+    }
+
+    private fun gb(v: LongArray, ia: Int, ib: Int, ic: Int, id: Int) {
+        var a = v[ia]; var b = v[ib]; var c = v[ic]; var d = v[id]
+        a += b + 2 * (a and M32) * (b and M32); d = (d xor a).rotateRight(32)
+        c += d + 2 * (c and M32) * (d and M32); b = (b xor c).rotateRight(24)
+        a += b + 2 * (a and M32) * (b and M32); d = (d xor a).rotateRight(16)
+        c += d + 2 * (c and M32) * (d and M32); b = (b xor c).rotateRight(63)
+        v[ia] = a; v[ib] = b; v[ic] = c; v[id] = d
+    }
+
+    internal fun le64(b: ByteArray, at: Int): Long {
+        var x = 0L
+        for (i in 7 downTo 0) x = (x shl 8) or (b[at + i].toLong() and 0xFF)
+        return x
+    }
+
+    internal fun putLe64(b: ByteArray, at: Int, x: Long) {
+        for (i in 0 until 8) b[at + i] = (x ushr (8 * i)).toByte()
+    }
+
+    private fun putLe32(b: ByteArray, at: Int, x: Int) {
+        for (i in 0 until 4) b[at + i] = (x ushr (8 * i)).toByte()
+    }
+}
+
+/** BLAKE2b (RFC 7693), unkeyed, one call for the whole input. */
+internal object Blake2b {
+    private val IV = longArrayOf(
+        0x6a09e667f3bcc908L, 0xbb67ae8584caa73bUL.toLong(), 0x3c6ef372fe94f82bL, 0xa54ff53a5f1d36f1UL.toLong(),
+        0x510e527fade682d1L, 0x9b05688c2b3e6c1fUL.toLong(), 0x1f83d9abfb41bd6bL, 0x5be0cd19137e2179L,
+    )
+    private val SIGMA = arrayOf(
+        byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15),
+        byteArrayOf(14, 10, 4, 8, 9, 15, 13, 6, 1, 12, 0, 2, 11, 7, 5, 3),
+        byteArrayOf(11, 8, 12, 0, 5, 2, 15, 13, 10, 14, 3, 6, 7, 1, 9, 4),
+        byteArrayOf(7, 9, 3, 1, 13, 12, 11, 14, 2, 6, 5, 10, 4, 0, 15, 8),
+        byteArrayOf(9, 0, 5, 7, 2, 4, 10, 15, 14, 1, 11, 12, 6, 8, 3, 13),
+        byteArrayOf(2, 12, 6, 10, 0, 11, 8, 3, 4, 13, 7, 5, 15, 14, 1, 9),
+        byteArrayOf(12, 5, 1, 15, 14, 13, 4, 10, 0, 7, 6, 3, 9, 2, 8, 11),
+        byteArrayOf(13, 11, 7, 14, 12, 1, 3, 9, 5, 0, 15, 4, 8, 6, 2, 10),
+        byteArrayOf(6, 15, 14, 9, 11, 3, 0, 8, 12, 2, 13, 7, 1, 4, 10, 5),
+        byteArrayOf(10, 2, 8, 4, 7, 6, 1, 5, 15, 11, 9, 14, 3, 12, 13, 0),
+    )
+
+    fun hash(input: ByteArray, outLen: Int): ByteArray {
+        require(outLen in 1..64)
+        val h = IV.copyOf()
+        h[0] = h[0] xor (0x01010000L or outLen.toLong())
+        val m = LongArray(16)
+        val v = LongArray(16)
+        val block = ByteArray(128)
+        try {
+            // Every block but the last is full; the last is 1..128 bytes (0 only for an empty input).
+            var at = 0
+            while (input.size - at > 128) {
+                input.copyInto(block, 0, at, at + 128)
+                at += 128
+                compress(h, m, v, block, at.toLong(), false)
+            }
+            block.fill(0)
+            input.copyInto(block, 0, at, input.size)
+            compress(h, m, v, block, input.size.toLong(), true)
+            val full = ByteArray(64)
+            for (i in 0 until 8) Argon2id.putLe64(full, 8 * i, h[i])
+            val out = full.copyOf(outLen)
+            full.fill(0)
+            return out
+        } finally {
+            h.fill(0); m.fill(0); v.fill(0); block.fill(0)
+        }
+    }
+
+    private fun compress(h: LongArray, m: LongArray, v: LongArray, block: ByteArray, count: Long, last: Boolean) {
+        for (i in 0 until 16) m[i] = Argon2id.le64(block, 8 * i)
+        for (i in 0 until 8) { v[i] = h[i]; v[i + 8] = IV[i] }
+        v[12] = v[12] xor count // inputs here are far below 2^64 bytes: the high counter word stays zero
+        if (last) v[14] = v[14].inv()
+        for (round in 0 until 12) {
+            val s = SIGMA[round % 10]
+            g(v, m, 0, 4, 8, 12, s[0], s[1]); g(v, m, 1, 5, 9, 13, s[2], s[3])
+            g(v, m, 2, 6, 10, 14, s[4], s[5]); g(v, m, 3, 7, 11, 15, s[6], s[7])
+            g(v, m, 0, 5, 10, 15, s[8], s[9]); g(v, m, 1, 6, 11, 12, s[10], s[11])
+            g(v, m, 2, 7, 8, 13, s[12], s[13]); g(v, m, 3, 4, 9, 14, s[14], s[15])
+        }
+        for (i in 0 until 8) h[i] = h[i] xor v[i] xor v[i + 8]
+    }
+
+    private fun g(v: LongArray, m: LongArray, ia: Int, ib: Int, ic: Int, id: Int, x: Byte, y: Byte) {
+        var a = v[ia]; var b = v[ib]; var c = v[ic]; var d = v[id]
+        a += b + m[x.toInt()]; d = (d xor a).rotateRight(32)
+        c += d; b = (b xor c).rotateRight(24)
+        a += b + m[y.toInt()]; d = (d xor a).rotateRight(16)
+        c += d; b = (b xor c).rotateRight(63)
+        v[ia] = a; v[ib] = b; v[ic] = c; v[id] = d
+    }
+}
diff --git a/core/src/main/kotlin/rs/zivke/seedtag/core/SeedBox.kt b/core/src/main/kotlin/rs/zivke/seedtag/core/SeedBox.kt
index bff1845..a0df716 100644
--- a/core/src/main/kotlin/rs/zivke/seedtag/core/SeedBox.kt
+++ b/core/src/main/kotlin/rs/zivke/seedtag/core/SeedBox.kt
@@ -3,8 +3,6 @@
 
 package rs.zivke.seedtag.core
 
-import org.bouncycastle.crypto.generators.Argon2BytesGenerator
-import org.bouncycastle.crypto.params.Argon2Parameters
 import java.nio.ByteBuffer
 import java.nio.CharBuffer
 import java.nio.charset.CharacterCodingException
@@ -181,16 +179,7 @@ object SeedBox {
     fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
         val pw = passwordBytes(password)
         try {
-            val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
-                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
-                .withIterations(ARGON_ITERATIONS)
-                .withMemoryAsKB(ARGON_MEMORY_KIB)
-                .withParallelism(ARGON_PARALLELISM)
-                .withSalt(salt)
-                .build()
-            val out = ByteArray(KEY_LEN)
-            Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, out)
-            return out
+            return Argon2id.hash(pw, salt, ARGON_ITERATIONS, ARGON_MEMORY_KIB, KEY_LEN)
         } finally {
             pw.fill(0)
         }
diff --git a/core/src/test/kotlin/rs/zivke/seedtag/core/Argon2idTest.kt b/core/src/test/kotlin/rs/zivke/seedtag/core/Argon2idTest.kt
new file mode 100644
index 0000000..4032842
--- /dev/null
+++ b/core/src/test/kotlin/rs/zivke/seedtag/core/Argon2idTest.kt
@@ -0,0 +1,69 @@
+// SPDX-License-Identifier: GPL-3.0-or-later
+// Copyright (C) 2026 zivke
+package rs.zivke.seedtag.core
+
+import org.bouncycastle.crypto.digests.Blake2bDigest
+import org.bouncycastle.crypto.generators.Argon2BytesGenerator
+import org.bouncycastle.crypto.params.Argon2Parameters
+import org.junit.Assert.assertArrayEquals
+import org.junit.Test
+import java.util.Random
+
+/** Our Argon2id and BLAKE2b against Bouncy Castle's, which is a test-only dependency. */
+class Argon2idTest {
+    private fun bc(pw: ByteArray, salt: ByteArray, t: Int, m: Int, len: Int): ByteArray {
+        val params = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
+            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
+            .withIterations(t).withMemoryAsKB(m).withParallelism(1).withSalt(salt).build()
+        return ByteArray(len).also { Argon2BytesGenerator().apply { init(params) }.generateBytes(pw, it) }
+    }
+
+    @Test
+    fun blake2bMatchesBouncyCastle() {
+        val rnd = Random(1)
+        for (n in (0..300) + listOf(511, 512, 513, 1024, 1028, 4097)) {
+            val data = ByteArray(n).also { rnd.nextBytes(it) }
+            for (len in intArrayOf(1, 16, 32, 63, 64)) {
+                val want = ByteArray(len)
+                Blake2bDigest(len * 8).apply { update(data, 0, n) }.doFinal(want, 0)
+                assertArrayEquals("n=$n len=$len", want, Blake2b.hash(data, len))
+            }
+        }
+    }
+
+    @Test
+    fun argon2idMatchesBouncyCastle() {
+        val rnd = Random(2)
+        for (m in intArrayOf(8, 9, 11, 16, 37, 64, 255, 256, 1024, 4099)) {
+            for (t in 1..4) {
+                for (len in intArrayOf(4, 16, 32, 64, 65, 97, 200)) {
+                    val pw = ByteArray(rnd.nextInt(260)).also { rnd.nextBytes(it) }
+                    val salt = ByteArray(8 + rnd.nextInt(40)).also { rnd.nextBytes(it) }
+                    assertArrayEquals("m=$m t=$t len=$len", bc(pw, salt, t, m, len), Argon2id.hash(pw, salt, t, m, len))
+                }
+            }
+        }
+        // Every password length around BLAKE2b's block edges, at small parameters.
+        for (n in 0..300) {
+            val pw = ByteArray(n).also { rnd.nextBytes(it) }
+            val salt = ByteArray(16).also { rnd.nextBytes(it) }
+            assertArrayEquals("pw length $n", bc(pw, salt, 1, 8, 32), Argon2id.hash(pw, salt, 1, 8, 32))
+        }
+    }
+
+    @Test
+    fun fullParametersMatchAndTiming() {
+        val pw = "correct horse battery staple".toByteArray()
+        val salt = ByteArray(16) { it.toByte() }
+        var bcMs = Long.MAX_VALUE
+        var ownMs = Long.MAX_VALUE
+        var a = ByteArray(0)
+        var b = ByteArray(0)
+        repeat(4) {
+            var t0 = System.nanoTime(); a = bc(pw, salt, 3, 65536, 32); bcMs = minOf(bcMs, (System.nanoTime() - t0) / 1_000_000)
+            t0 = System.nanoTime(); b = Argon2id.hash(pw, salt, 3, 65536, 32); ownMs = minOf(ownMs, (System.nanoTime() - t0) / 1_000_000)
+        }
+        assertArrayEquals(a, b)
+        println("TIMING t=3 m=64MiB: Bouncy Castle $bcMs ms, own $ownMs ms (best of 4)")
+    }
+}
diff --git a/gradle/libs.versions.toml b/gradle/libs.versions.toml
index 19fe53a..0a7d173 100644
--- a/gradle/libs.versions.toml
+++ b/gradle/libs.versions.toml
@@ -15,7 +15,7 @@ compose-material3 = { group = "androidx.compose.material3", name = "material3" }
 compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
 compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
 activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activityCompose" }
-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
+lifecycle-viewmodel = { group = "androidx.lifecycle", name = "lifecycle-viewmodel", version.ref = "lifecycle" }
 bouncycastle = { group = "org.bouncycastle", name = "bcprov-jdk18on", version.ref = "bouncycastle" }
 junit = { group = "junit", name = "junit", version.ref = "junit" }
 
diff --git a/tools/check-apk.sh b/tools/check-apk.sh
index 960aac6..bc6d7e6 100755
--- a/tools/check-apk.sh
+++ b/tools/check-apk.sh
@@ -89,9 +89,7 @@ components="$(printf '%s\n' "$manifest" | awk '
     /^ *E: (activity|activity-alias|service|receiver|provider) /{ want = $2; next }
     want && /^ *E: /{ print want, "(unnamed)"; want = "" }
     want && /:name\(0x01010003\)="/{ match($0, /="[^"]*"/); print want, substr($0, RSTART + 2, RLENGTH - 3); want = "" }')"
-allowed="activity rs.zivke.seedtag.MainActivity
-provider androidx.startup.InitializationProvider
-receiver androidx.profileinstaller.ProfileInstallReceiver"
+allowed="activity rs.zivke.seedtag.MainActivity"
 if [ "$DEBUG" = 1 ]; then allowed="$allowed
 activity androidx.compose.ui.tooling.PreviewActivity"; fi
 unknown="$(printf '%s\n' "$components" | grep -vxF "$allowed" || true)"
```
