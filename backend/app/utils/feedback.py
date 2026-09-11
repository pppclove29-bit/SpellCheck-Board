import re
from collections.abc import Sequence
from typing import Literal, Protocol

from app.schemas import FeedbackMode

_WHITESPACE = re.compile(r"\s+")


class FeedbackSource(Protocol):
    @property
    def original_word(self) -> str: ...
    @property
    def suggested_word(self) -> str: ...
    @property
    def reason(self) -> str: ...
    @property
    def wit(self) -> str | None: ...


class Span(Protocol):
    @property
    def offset(self) -> int: ...
    @property
    def length(self) -> int: ...
    @property
    def suggested_word(self) -> str: ...


def rule_suggestion_type(original: str, suggested: str) -> Literal["spelling", "spacing"]:
    """Same letters once whitespace is removed → only spacing changed."""
    return "spacing" if _WHITESPACE.sub("", original) == _WHITESPACE.sub("", suggested) else "spelling"


def compose_rule_feedback(corrections: Sequence[FeedbackSource], mode: FeedbackMode, templates: dict[FeedbackMode, str]) -> str | None:
    """On-device feedback algorithm: first correction, rule wit for spicy_wit, else the mode template."""
    if not corrections:
        return None
    first = corrections[0]
    if mode == "spicy_wit" and first.wit:
        return first.wit
    return (
        templates[mode]
        .replace("{original}", first.original_word)
        .replace("{suggested}", first.suggested_word)
        .replace("{reason}", first.reason)
    )


def apply_corrections(text: str, corrections: Sequence[Span]) -> str:
    """Applies non-overlapping corrections (code-point offsets, any order)."""
    for c in sorted(corrections, key=lambda c: c.offset, reverse=True):
        text = text[: c.offset] + c.suggested_word + text[c.offset + c.length :]
    return text
