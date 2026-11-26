#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Live differential test between seedtag.py and the app's core (board B47, `make test-cross`).

    cross_test.py seal FILE   random backups sealed here, for CrossTest.kt to open
    cross_test.py open FILE   open the backups CrossTest.kt sealed, and compare

Each line: password (hex of UTF-8), entropy (hex), the tag's text. Random throwaway data, never a real seed.
"""
import base64
import os
import pathlib
import random
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import seedtag  # noqa: E402

CASES = 12
# Password characters: ASCII, spaces inside, accents (some NFKD changes), outside the BMP.
ALPHABET = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789!#$%&*+-./:;=?@ äöüßçñåøÅﬁ①€😀𝔘"


def password(rng: random.Random) -> str:
    while True:
        pw = "".join(rng.choice(ALPHABET) for _ in range(rng.randint(1, 40)))
        if not seedtag.has_stray_spaces(pw):
            return pw


def main(argv) -> int:
    if len(argv) != 2 or argv[0] not in ("seal", "open"):
        print(__doc__, file=sys.stderr)
        return 2
    path = pathlib.Path(argv[1])
    if argv[0] == "seal":
        rng = random.Random(os.urandom(16))
        lines = []
        for i in range(CASES):
            entropy = os.urandom((16, 20, 24, 28, 32)[i % 5])
            pw = password(rng)
            text = base64.b64encode(seedtag.seal(entropy, pw)).decode()
            lines.append(f"{pw.encode().hex()}\t{entropy.hex()}\t{text}")
        path.write_text("\n".join(lines) + "\n", encoding="utf-8")
        print(f"seedtag.py sealed {len(lines)} backups for the app's core")
        return 0
    failed = 0
    rows = [line.split("\t") for line in path.read_text(encoding="utf-8").splitlines() if line]
    for pw, entropy, text in rows:
        got = seedtag.open_blob(seedtag.blob_from_text(text), bytes.fromhex(pw).decode())
        if got.hex() != entropy:
            failed += 1
    print(f"seedtag.py opened {len(rows) - failed} of {len(rows)} backups the app's core sealed")
    return 1 if failed or len(rows) < CASES else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
