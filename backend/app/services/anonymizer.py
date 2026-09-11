"""PII 익명화 레이어: LLM 전송 전 주민번호/카드/전화/이메일/계좌번호를 플레이스홀더로 치환."""

import re
from dataclasses import dataclass, field

# Order = priority when spans overlap. [0-9] instead of \d: Python's \d also matches non-ASCII digits.
_PII_RULES: list[tuple[str, re.Pattern[str]]] = [
    # YYMMDD must be a plausible date so 13-digit account numbers aren't misread as RRNs.
    ("RRN", re.compile(r"(?<![0-9])[0-9]{2}(?:0[1-9]|1[0-2])(?:0[1-9]|[12][0-9]|3[01])-?[1-8][0-9]{6}(?![0-9])")),
    ("CARD", re.compile(r"(?<![0-9])[0-9]{4}[- ]?[0-9]{4}[- ]?[0-9]{4}[- ]?[0-9]{4}(?![0-9])")),
    ("PHONE", re.compile(r"(?<![0-9])(?:01[016789]|070|0[2-6][0-9]?)[- .]?[0-9]{3,4}[- .]?[0-9]{4}(?![0-9])")),
    ("EMAIL", re.compile(r"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}")),
    ("ACCOUNT", re.compile(r"(?<![0-9])[0-9]{2,6}-[0-9]{2,6}-[0-9]{2,8}(?:-[0-9]{1,3})?(?![0-9])")),
    ("ACCOUNT", re.compile(r"(?<![0-9])[0-9]{10,16}(?![0-9])")),
]

_PLACEHOLDER = re.compile(r"\[(?:RRN|CARD|PHONE|EMAIL|ACCOUNT)_\d+\]")


def contains_placeholder(text: str) -> bool:
    return _PLACEHOLDER.search(text) is not None


@dataclass(frozen=True)
class MaskEntry:
    placeholder: str
    value: str
    original_start: int
    masked_start: int


@dataclass(frozen=True)
class MaskResult:
    masked: str
    entries: list[MaskEntry] = field(default_factory=list)

    def to_original_offset(self, masked_offset: int) -> int:
        """Maps a code-point offset in `masked` back to the original text. Inside a placeholder → its start."""
        delta = 0
        for e in self.entries:
            if masked_offset < e.masked_start:
                break
            if masked_offset < e.masked_start + len(e.placeholder):
                return e.original_start
            delta += len(e.value) - len(e.placeholder)
        return masked_offset + delta

    def unmask(self, text: str) -> str:
        """Restores original values in text produced from the masked input (e.g. LLM feedback)."""
        for e in self.entries:
            text = text.replace(e.placeholder, e.value)
        return text


def mask_pii(text: str) -> MaskResult:
    spans: list[tuple[int, int, str]] = []
    for kind, regex in _PII_RULES:
        for m in regex.finditer(text):
            start, end = m.span()
            if all(end <= s or start >= e for s, e, _ in spans):
                spans.append((start, end, kind))
    spans.sort()

    counters: dict[str, int] = {}
    entries: list[MaskEntry] = []
    parts: list[str] = []
    masked_len = 0
    cursor = 0
    for start, end, kind in spans:
        parts.append(text[cursor:start])
        masked_len += start - cursor
        counters[kind] = counters.get(kind, 0) + 1
        placeholder = f"[{kind}_{counters[kind]}]"
        entries.append(MaskEntry(placeholder, text[start:end], start, masked_len))
        parts.append(placeholder)
        masked_len += len(placeholder)
        cursor = end
    parts.append(text[cursor:])
    return MaskResult("".join(parts), entries)
