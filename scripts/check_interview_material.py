"""Check the maintained interview counts and market evidence denominators."""

from __future__ import annotations

import json
import re
from collections import Counter
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main() -> None:
    main_text = (ROOT / "面经-MemOS.md").read_text()
    extra = (ROOT / "docs/interview/market-star-qa.md").read_text()
    core_count = len(re.findall(r"^### Q\d+：", main_text, re.MULTILINE))
    follow_count = len(re.findall(r"^#### 追问", main_text, re.MULTILINE))
    extra_core = len(re.findall(r"^### Q\d+：", extra, re.MULTILINE))
    extra_follow = len(re.findall(r"^#### F\d+：", extra, re.MULTILINE))
    assert (core_count, follow_count, extra_core, extra_follow) == (22, 25, 3, 25)
    lengths = []
    for section in re.split(r"^#{3,4} [QF]\d+：", extra, flags=re.MULTILINE)[1:]:
        answer = section.split("\n\n", 1)[1].split("\n\n", 1)[0]
        assert all(marker in answer for marker in ("S：", "T：", "A：", "R："))
        length = len(re.findall(r"[\u4e00-\u9fff]", answer))
        assert length >= 150, (section.splitlines()[0], length)
        lengths.append(length)
    corpus = ROOT / "docs/research/market-2026-09-07"
    rows = json.loads((corpus / "nowcoder-registry.json").read_text())
    jobs = json.loads((corpus / "jd-registry.json").read_text())
    assert len(rows) == len({row["url"] for row in rows}) == 123
    counts = Counter(row["access"] for row in rows)
    assert counts == {"PUBLIC_BODY": 114, "PUBLIC_PREVIEW": 2, "UNAVAILABLE": 7}
    readable = [row for row in rows if row["access"] != "UNAVAILABLE"]
    assert Counter(row["grade"] for row in readable) == {"A": 80, "B": 13, "C": 23}
    assert len({row["id"] for row in rows}) == len(rows)
    assert all(
        row["body_chars"] > 0 and re.fullmatch(r"[0-9a-f]{64}", row["body_sha256"])
        for row in readable
    )
    social = [row for row in rows if row["social_hire"] and row["grade"] == "A"]
    assert len(social) == 22
    assert len({row["author_group"] for row in social}) == 7
    assert all(row["access"] != "UNAVAILABLE" for row in social)
    assert Counter(row["author_group"] for row in social).most_common(1)[0][1] == 16
    assert {row["id"] for row in social if row["explicit_1_to_3_years"]} == {
        "N02",
        "N95",
        "N101",
        "N112",
    }
    assert len(jobs) == 32
    assert Counter(row["evidence_level"] for row in jobs) == {
        "官方全文": 5,
        "BOSS公开摘要": 27,
    }
    print(
        f"STAR: 25 main / 50 follow-ups; supplement min Chinese chars={min(lengths)}; "
        f"Nowcoder={dict(counts)}; social A=22, author groups=7; JD=5 full+27 snippets"
    )


if __name__ == "__main__":
    main()
