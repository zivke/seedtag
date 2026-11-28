#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Check the licence information (boards B19 and B39).

- The licence texts the About page reads from app/src/main/res/raw/ stay byte for byte LICENSE and LICENSES/,
  and the two MIT notices keep their copyright lines and the MIT permission text.
- Every file git tracks has its licence recorded, as REUSE does it: an SPDX-License-Identifier line near its top,
  a .license file next to it, or an annotation in REUSE.toml. Every licence named has its text in LICENSES/.
  (Like `reuse lint`, without the `reuse` tool, which would be a new dependency.)

    python3 tools/check-licenses.py    # exits 1 on any problem (part of make check)
"""
import os
import re
import subprocess
import sys
import tomllib

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


# Files that are licence texts themselves: LICENSE, LICENSES/ and the app's verbatim copies (checked above).
TEXTS = {"LICENSE"} | {b for _, b in SAME} | {f"app/src/main/res/raw/{n}" for n in NOTICES}
SPDX = re.compile(r"SPDX-License-Identifier:\s*([A-Za-z0-9.+ ()-]+?)\s*(?:-->|\*/)?\s*$", re.M)


def glob_re(pattern):
    """REUSE.toml's globs: ** matches across directories, * within one."""
    out = "".join(".*" if part == "**" else "[^/]*" if part == "*" else re.escape(part)
                  for part in re.split(r"(\*\*|\*)", pattern))
    return re.compile(out + "$")


def ids(expression):
    return {t for t in re.split(r"\s+|[()]", expression) if t and t not in ("AND", "OR", "WITH")}


def coverage_problems():
    found = []
    with open(os.path.join(ROOT, "REUSE.toml"), "rb") as f:
        annotations = tomllib.load(f).get("annotations", [])
    globs = []
    for a in annotations:
        paths = a["path"] if isinstance(a["path"], list) else [a["path"]]
        if "SPDX-FileCopyrightText" not in a or "SPDX-License-Identifier" not in a:
            found.append(f"REUSE.toml: an annotation for {paths[0]} lacks a copyright or licence")
        globs += [(glob_re(p), a.get("SPDX-License-Identifier", "")) for p in paths]
    used = set()
    files = subprocess.run(["git", "ls-files", "-z"], cwd=ROOT, capture_output=True, check=True).stdout.decode()
    tracked = [f for f in files.split("\0") if f]
    for rel in tracked:
        if rel in TEXTS or rel.startswith("LICENSES/") or rel.endswith(".license"):
            continue
        header = ""
        try:
            with open(os.path.join(ROOT, rel), "rb") as f:
                header = f.read(4000).decode("utf-8", "replace")
        except FileNotFoundError:
            continue  # deleted in the working tree
        own = SPDX.findall(header)
        if own:
            for expression in own:  # the file's own, and a snippet's (seedtag.py's wordlist)
                used |= ids(expression)
            if "Copyright" not in header and "SPDX-FileCopyrightText" not in header:
                found.append(f"{rel}: an SPDX line but no copyright line")
            continue
        sidecar = os.path.join(ROOT, rel + ".license")
        if os.path.exists(sidecar):
            with open(sidecar, encoding="utf-8") as f:
                used |= ids(" ".join(SPDX.findall(f.read())))
            continue
        matched = [lic for g, lic in globs if g.match(rel)]
        if matched:
            used |= ids(matched[-1])
        else:
            found.append(f"{rel}: no licence (an SPDX line, a .license file or a REUSE.toml annotation)")
    for lic in sorted(used):
        if not os.path.exists(os.path.join(ROOT, "LICENSES", f"{lic}.txt")):
            found.append(f"licence {lic} is used but LICENSES/{lic}.txt is missing")
    return found, len(tracked)


def main():
    found = problems()
    covered, count = coverage_problems()
    found += covered
    for p in found:
        print(f"!! {p}", file=sys.stderr)
    if not found:
        print(f">> licences OK: the app's texts agree with LICENSE and LICENSES/, all {count} tracked files covered")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
