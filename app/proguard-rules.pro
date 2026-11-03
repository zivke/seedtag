# Bouncy Castle: only the Argon2 generator is used, referenced directly (no reflection).
-dontwarn org.bouncycastle.**
# Keep the BIP39 wordlist resource lookup stable.
-keep class rs.zivke.seedtag.core.Bip39 { *; }
