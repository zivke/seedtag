#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
# Copyright (C) 2026 zivke
"""Check the GitHub Actions workflows before they're pushed.

There's no way to run a workflow in the dev container, so this catches what can be caught
offline: each file under .github/workflows must be valid YAML with triggers and jobs, declare
`permissions`, and pin every action to a full commit SHA (a tag like @v4 can be moved to
other code).

    python3 tools/check-workflows.py    # exits 1 on any problem
"""
import glob
import os
import re
import sys

import yaml

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..")
PINNED = re.compile(r"^[^@\s]+@[0-9a-f]{40}$")


def check(path):
    problems = []
    with open(path, encoding="utf-8") as f:
        try:
            wf = yaml.safe_load(f)
        except yaml.YAMLError as e:
            return [f"not valid YAML: {e}"]
    if not isinstance(wf, dict):
        return ["not a YAML mapping"]
    # YAML 1.1 reads a bare `on` key as the boolean true.
    triggers = wf.get("on", wf.get(True))
    if not triggers:
        problems.append("no triggers (`on:`)")
    if "permissions" not in wf:
        problems.append("no top-level `permissions:`")
    jobs = wf.get("jobs")
    if not isinstance(jobs, dict) or not jobs:
        return problems + ["no jobs"]
    for name, job in jobs.items():
        if not isinstance(job, dict):
            problems.append(f"job {name}: not a mapping")
            continue
        uses = [job["uses"]] if "uses" in job else []
        steps = job.get("steps", [])
        if not uses and not steps:
            problems.append(f"job {name}: no steps")
        for i, step in enumerate(steps, 1):
            if not isinstance(step, dict) or not ("run" in step or "uses" in step):
                problems.append(f"job {name}, step {i}: neither `run` nor `uses`")
            elif "uses" in step:
                uses.append(step["uses"])
        for u in uses:
            if not (u.startswith("./") or PINNED.match(u)):
                problems.append(f"job {name}: `{u}` isn't pinned to a full commit SHA")
    return problems


def main():
    files = sorted(glob.glob(os.path.join(ROOT, ".github", "workflows", "*.y*ml")))
    if not files:
        print("no workflow files found", file=sys.stderr)
        return 1
    failed = False
    for path in files:
        rel = os.path.relpath(path, ROOT)
        problems = check(path)
        for p in problems:
            print(f"{rel}: {p}", file=sys.stderr)
        failed |= bool(problems)
        if not problems:
            print(f"{rel}: OK")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
