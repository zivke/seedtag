#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Check that the licence texts the app shows are the ones in the repository (board B19).

The About page reads them from app/src/main/res/raw/; they must stay byte for byte LICENSE and LICENSES/, and
the two MIT notices must keep their copyright lines and the MIT permission text.

    python3 tools/check-licenses.py    # exits 1 on any problem (part of make check)
"""
import os
import sys

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
RAW = os.path.join(ROOT, "app", "src", "main", "res", "raw")
SAME = [
    ("LICENSE", "LICENSES/GPL-3.0-or-later.txt"),
    ("LICENSE", "app/src/main/res/raw/license_gpl_3_0.txt"),
    ("LICENSES/Apache-2.0.txt", "app/src/main/res/raw/license_apache_2_0.txt"),
]
NOTICES = {
    "license_mit_bouncycastle.txt": "Copyright (c) 2000-2026 The Legion of the Bouncy Castle Inc.",
    "license_mit_bip39.txt": "BIP-0039 falls under the MIT License",
}


def read(rel):
    with open(os.path.join(ROOT, rel), "rb") as f:
        return f.read()


def problems():
    found = []
    for a, b in SAME:
        if read(a) != read(b):
            found.append(f"{b} isn't the same as {a}")
    mit = read("LICENSES/MIT.txt").decode("utf-8")
    permission = " ".join(mit[mit.index("Permission is hereby granted"):].split())
    for name, line in NOTICES.items():
        text = " ".join(read(os.path.join("app/src/main/res/raw", name)).decode("utf-8").split())
        if line not in text:
            found.append(f"res/raw/{name} lacks \"{line}\"")
        # Bouncy Castle words its MIT notice slightly differently ("sub license"): kept as its jar has it, so
        # only the notice's two key sentences are checked there; SeedTag's own BIP-0039 notice is the full text.
        wanted = [permission] if name == "license_mit_bip39.txt" else [
            "Permission is hereby granted, free of charge", "THE SOFTWARE IS PROVIDED \"AS IS\", WITHOUT WARRANTY"]
        for w in wanted:
            if w not in text:
                found.append(f"res/raw/{name} lacks the MIT permission notice (\"{w[:40]}…\")")
    return found


def main():
    found = problems()
    for p in found:
        print(f"!! {p}", file=sys.stderr)
    if not found:
        print(">> licence texts OK (LICENSE, LICENSES/ and the app's copies agree)")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
