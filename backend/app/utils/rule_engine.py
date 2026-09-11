"""1차 온디바이스/Local 검사 엔진. Android 키보드와 동일 알고리즘 (docs/api-contract.md)."""

import json
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any, TypeVar

from app.schemas import FeedbackMode

# Synced copy of ../shared/korean-rules.json (scripts/sync_rules.py), bundled with the Vercel function.
RULES_PATH = Path(__file__).resolve().parent.parent / "data" / "korean-rules.json"

_GROUP_REF = re.compile(r"\$(\d)")


@dataclass(frozen=True)
class Correction:
    offset: int  # code points into the Python str
    length: int  # code points
    original_word: str
    suggested_word: str
    reason: str
    wit: str | None = None  # rule-specific spicy_wit line


def load_rules(path: Path = RULES_PATH) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def _expand(replacement: str, match: re.Match[str]) -> str:
    """Replaces $0 (whole match) and $1..$9 (groups). Never re-run the regex on the match: lookarounds need the full text."""

    def group(ref: re.Match[str]) -> str:
        n = int(ref.group(1))
        return (match.group(n) or "") if n <= match.re.groups else ""

    return _GROUP_REF.sub(group, replacement)


T = TypeVar("T", bound=Correction)


def select_non_overlapping(candidates: list[T]) -> list[T]:
    """Stable sort by (offset asc, length desc), then greedily keep non-overlapping spans.

    Ties keep input order, so callers control priority by candidate order.
    """
    selected: list[T] = []
    end = 0
    for c in sorted(candidates, key=lambda c: (c.offset, -c.length)):
        if c.offset >= end:
            selected.append(c)
            end = c.offset + c.length
    return selected


class RuleEngine:
    def __init__(self, rules: dict[str, Any] | None = None) -> None:
        self.rules = rules if rules is not None else load_rules()
        self.templates: dict[FeedbackMode, str] = self.rules["feedback_templates"]
        self._dictionary = [d for d in self.rules["dictionary"] if d["from"]]
        self._patterns = [(p, re.compile(p["pattern"])) for p in self.rules["patterns"]]

    def check(self, text: str) -> list[Correction]:
        candidates: list[Correction] = []

        for d in self._dictionary:
            i = text.find(d["from"])
            while i != -1:
                candidates.append(Correction(i, len(d["from"]), d["from"], d["to"], d["reason"], d.get("wit")))
                i = text.find(d["from"], i + 1)

        for rule, regex in self._patterns:
            for m in regex.finditer(text):
                if not m.group(0):
                    continue
                candidates.append(
                    Correction(m.start(), len(m.group(0)), m.group(0), _expand(rule["replacement"], m), rule["reason"], rule.get("wit"))
                )

        return select_non_overlapping([c for c in candidates if c.suggested_word != c.original_word])
