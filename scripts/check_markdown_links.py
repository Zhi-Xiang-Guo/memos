#!/usr/bin/env python3
"""Fail when a repository-local Markdown file or heading link is broken."""

from __future__ import annotations

import re
import sys
import unicodedata
from pathlib import Path
from urllib.parse import unquote

ROOT = Path(__file__).resolve().parents[1]
LINK_PATTERN = re.compile(r"(?<!!)\[[^\]]*\]\(([^)]+)\)")
HEADING_PATTERN = re.compile(r"^#{1,6}\s+(.+?)\s*#*\s*$", re.MULTILINE)


def github_slug(text: str) -> str:
    text = re.sub(r"<[^>]+>", "", text)
    text = re.sub(r"[`*_~]", "", text).strip().lower()
    text = "".join(char for char in text if unicodedata.category(char)[0] != "P" or char in "-_ ")
    return re.sub(r"\s+", "-", text)


def anchors(path: Path) -> set[str]:
    counts: dict[str, int] = {}
    result: set[str] = set()
    for heading in HEADING_PATTERN.findall(path.read_text(encoding="utf-8")):
        base = github_slug(heading)
        count = counts.get(base, 0)
        counts[base] = count + 1
        result.add(base if count == 0 else f"{base}-{count}")
    return result


def check_file(path: Path) -> list[str]:
    failures: list[str] = []
    text = path.read_text(encoding="utf-8")
    for raw_target in LINK_PATTERN.findall(text):
        target = raw_target.strip().split()[0].strip("<>")
        if not target or target.startswith(("http://", "https://", "mailto:", "#")):
            if target.startswith("#") and target[1:] not in anchors(path):
                failures.append(f"{path.relative_to(ROOT)}: missing anchor {target}")
            continue
        file_part, separator, anchor = target.partition("#")
        destination = (path.parent / unquote(file_part)).resolve()
        if ROOT not in destination.parents and destination != ROOT:
            failures.append(f"{path.relative_to(ROOT)}: escapes repository: {target}")
            continue
        if not destination.is_file():
            failures.append(f"{path.relative_to(ROOT)}: missing file {target}")
            continue
        if separator and destination.suffix.lower() == ".md" and anchor not in anchors(destination):
            failures.append(f"{path.relative_to(ROOT)}: missing anchor {target}")
    return failures


def main() -> int:
    failures = [failure for path in ROOT.rglob("*.md") for failure in check_file(path)]
    if failures:
        print("\n".join(failures), file=sys.stderr)
        return 1
    print(f"checked {len(list(ROOT.rglob('*.md')))} Markdown files")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
