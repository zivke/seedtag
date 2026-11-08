#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke85
"""Cross-check gradle/verification-metadata.xml against the repositories.

`make deps-metadata` records the SHA-256 of every dependency file in Gradle's local cache, so
it trusts whatever was downloaded first. This checks each of those files against what its
repository publishes (the .sha1 next to it, or the file itself when there's no .sha1), so a
poisoned cache or a bad download is caught before the checksums are committed.

    python3 tools/check-deps.py     # needs network access; exits 1 on any mismatch
"""
import hashlib
import json
import os
import re
import sys
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET
from concurrent.futures import ThreadPoolExecutor

NS = "{https://schema.gradle.org/dependency-verification}"
META = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "gradle", "verification-metadata.xml")
CACHE = os.path.expanduser("~/.gradle/caches/modules-2/files-2.1")
REPOS = [  # the repositories in settings.gradle.kts
    "https://dl.google.com/dl/android/maven2/",
    "https://repo.maven.apache.org/maven2/",
    "https://plugins.gradle.org/m2/",
]


def fetch(url):
    for attempt in range(3):
        try:
            return urllib.request.urlopen(url, timeout=60).read()
        except urllib.error.HTTPError as e:
            if e.code == 404:
                return None
        except OSError:
            pass
    raise OSError(f"can't fetch {url}")


def remote_name(folder, name):
    """Gradle module metadata can give a file a logical name that differs from its URL."""
    for h in os.listdir(folder):
        for m in os.listdir(os.path.join(folder, h)):
            if m.endswith(".module"):
                with open(os.path.join(folder, h, m)) as fh:
                    for variant in json.load(fh).get("variants", []):
                        for f in variant.get("files", []):
                            if f.get("name") == name:
                                return f.get("url", name)
    return name


def check(job):
    group, module, version, name, want = job
    folder = os.path.join(CACHE, group, module, version)
    path = f"{group.replace('.', '/')}/{module}/{version}/{remote_name(folder, name) if os.path.isdir(folder) else name}"
    for repo in REPOS:
        sha1 = fetch(repo + path + ".sha1")
        if sha1 is not None:
            data = None
            if os.path.isdir(folder):
                for h in os.listdir(folder):
                    p = os.path.join(folder, h, name)
                    if os.path.exists(p):
                        with open(p, "rb") as fh:
                            candidate = fh.read()
                        if hashlib.sha256(candidate).hexdigest() == want:
                            data = candidate
            if data is not None:
                ok = hashlib.sha1(data).hexdigest() == sha1.decode().split()[0].strip().lower()
                return ("OK" if ok else "MISMATCH", job, repo)
        data = fetch(repo + path)
        if data is not None:
            return ("OK" if hashlib.sha256(data).hexdigest() == want else "MISMATCH", job, repo)
    return ("NOT FOUND", job, None)


def main():
    jobs = []
    for c in ET.parse(META).getroot().iter(NS + "component"):
        for a in c.iter(NS + "artifact"):
            jobs.append((c.get("group"), c.get("name"), c.get("version"), a.get("name"), a.find(NS + "sha256").get("value")))
    with ThreadPoolExecutor(6) as ex:
        results = list(ex.map(check, jobs))
    bad = [r for r in results if r[0] != "OK"]
    for status, (g, m, v, name, _), _ in bad:
        print(f"!! {status}: {g}:{m}:{v} {name}", file=sys.stderr)
    print(f">> {len(results) - len(bad)} of {len(results)} dependency files match their repository")
    return 1 if bad else 0


if __name__ == "__main__":
    sys.exit(main())
