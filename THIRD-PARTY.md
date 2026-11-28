<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
<!-- Copyright (C) 2026 zivke -->

# Third-party software and data in SeedTag

SeedTag is under the GNU GPL, version 3 or later ([LICENSE](LICENSE)). It includes these works by others, each
under its own licence. The full texts are in [LICENSES/](LICENSES), and the app shows them under
*About › Open-source licences* (from `app/src/main/res/raw/`, the same files).

## In the app (the APK)

| What | By | Licence |
|---|---|---|
| AndroidX (activity, core, lifecycle, savedstate, window, …) and Jetpack Compose | Google / The Android Open Source Project | Apache-2.0 |
| Five Material Symbols icons (`app/src/main/res/drawable/ic_*.xml`, all but the launcher icon; each file names its source) | Google | Apache-2.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization, JetBrains annotations | JetBrains | Apache-2.0 |
| JSpecify annotations | The JSpecify authors | Apache-2.0 |
| Bouncy Castle (`bcprov`), for Argon2id | The Legion of the Bouncy Castle Inc. | MIT |
| The BIP39 English wordlist (`core/src/main/resources/rs/zivke/seedtag/core/bip39-english.txt`) | BIP-0039: Marek Palatinus, Pavol Rusnak, Aaron Voisine, Sean Bowe | MIT |

The Bouncy Castle notice, as its jar ships it: "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.
(https://www.bouncycastle.org)", then the MIT permission notice. BIP-0039 has no copyright line; its *Copyright*
section says: "This BIP falls under the MIT License." The wordlist is byte for byte the one in
`bitcoin/bips`, `bip-0039/english.txt` (SHA-256 `2f5eed53a4727b4bf8880d8f3f199efc90e58503646d9ff8eff3a2ed3b24dbda`).

None of the libraries above ships a `NOTICE` file in the artifacts SeedTag uses (checked 2026-11-28).

## In the repository only

| What | Licence |
|---|---|
| `tools/seedtag.py`, `FORMAT.md` and SeedTag's own vector files | 0BSD (SeedTag's own, so recovery is never in doubt) |
| The wordlist embedded in `tools/seedtag.py` | MIT (BIP-0039, as above) |
| `core/src/test/resources/bip39-vectors.tsv`: Trezor's reference vectors (python-mnemonic `vectors.json`) | MIT, Copyright (c) 2013-2016 Pavol Rusnak |
| `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` | Apache-2.0, Copyright 2015-2021 the original authors (Gradle) |
