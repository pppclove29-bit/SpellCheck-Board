import re

import pytest

from app.utils.feedback import compose_rule_feedback, rule_suggestion_type
from app.utils.rule_engine import RuleEngine
from app.utils.text_offsets import cp_to_utf16, utf16_len
from tests.conftest import load_golden

GOLDEN = load_golden()
engine = RuleEngine()


def wire(text: str) -> list[dict]:
    """Engine output in contract (UTF-16) coordinates, as the golden file specifies."""
    return [
        {
            "offset": cp_to_utf16(text, c.offset),
            "length": utf16_len(c.original_word),
            "original_word": c.original_word,
            "suggested_word": c.suggested_word,
        }
        for c in engine.check(text)
    ]


@pytest.mark.parametrize("case", GOLDEN["cases"], ids=[c["text"] for c in GOLDEN["cases"]])
def test_golden_cases(case: dict) -> None:
    assert wire(case["text"]) == case["expected"]


@pytest.mark.parametrize("text", GOLDEN["clean_cases"], ids=GOLDEN["clean_cases"])
def test_correct_sentences_are_left_alone(text: str) -> None:
    """False-positive guard: a rule that fires on valid Korean is worse than a missed typo."""
    assert engine.check(text) == []


@pytest.mark.parametrize("case", GOLDEN["feedback_cases"], ids=[f"{c['mode']}:{c['text']}" for c in GOLDEN["feedback_cases"]])
def test_golden_feedback(case: dict) -> None:
    assert compose_rule_feedback(engine.check(case["text"]), case["mode"], engine.templates) == case["expected"]


@pytest.mark.parametrize("case", GOLDEN["type_cases"], ids=[c["original_word"] for c in GOLDEN["type_cases"]])
def test_golden_types(case: dict) -> None:
    assert rule_suggestion_type(case["original_word"], case["suggested_word"]) == case["type"]


def test_every_span_equals_the_text_slice() -> None:
    for case in GOLDEN["cases"]:
        for c in engine.check(case["text"]):
            assert case["text"][c.offset : c.offset + c.length] == c.original_word


def test_every_rule_compiles_and_has_a_reason() -> None:
    for p in engine.rules["patterns"]:
        re.compile(p["pattern"])
        assert p["reason"]
    assert all(d["reason"] for d in engine.rules["dictionary"])


def test_patterns_avoid_engine_dependent_shorthands() -> None:
    """\\b \\s \\d \\w differ across Java/ICU/Python/JS Unicode handling; rules must spell classes out."""
    for p in engine.rules["patterns"]:
        assert not re.search(r"\\[bBsSdDwW]", p["pattern"]), p["id"]


def test_dollar_zero_expands_to_whole_match() -> None:
    custom = RuleEngine(
        {
            "version": 2,
            "feedback_templates": {"spicy_wit": "", "police": "", "gentle": ""},
            "dictionary": [],
            "patterns": [{"id": "p", "pattern": "a(b)", "replacement": "[$0|$1|$7]", "reason": "p"}],
        }
    )
    assert [c.suggested_word for c in custom.check("xab")] == ["[ab|b|]"]


def test_lookarounds_see_the_full_text() -> None:
    assert engine.check("구지") == []
    assert [c.suggested_word for c in engine.check("구지 가야 해")] == ["굳이"]


def test_longest_candidate_wins_at_same_offset() -> None:
    assert [(c.original_word, c.suggested_word) for c in engine.check("안되요")] == [("안되요", "안 돼요")]


def test_ties_keep_generation_order_dictionary_first() -> None:
    custom = RuleEngine(
        {
            "version": 2,
            "feedback_templates": {"spicy_wit": "", "police": "", "gentle": ""},
            "dictionary": [{"from": "ab", "to": "DICT", "reason": "d"}],
            "patterns": [{"id": "p", "pattern": "(a)b", "replacement": "$1PAT", "reason": "p"}],
        }
    )
    assert [c.suggested_word for c in custom.check("xab")] == ["DICT"]


def test_noop_dropped_and_unmatched_groups_expand_empty() -> None:
    custom = RuleEngine(
        {
            "version": 2,
            "feedback_templates": {"spicy_wit": "", "police": "", "gentle": ""},
            "dictionary": [{"from": "same", "to": "same", "reason": "noop"}],
            "patterns": [{"id": "p", "pattern": "q(z)?", "replacement": "Q$1", "reason": "p"}],
        }
    )
    assert [c.suggested_word for c in custom.check("same q")] == ["Q"]
