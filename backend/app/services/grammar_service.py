from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal

from app.schemas import AiStatus, FeedbackMode, GrammarCheckResponse, Suggestion, SuggestionType
from app.services.ai_nlp import AiAnalysis, AiNlpService, AiSuggestion, AiUnavailableError
from app.services.anonymizer import MaskResult, contains_placeholder, mask_pii
from app.services.quota import QuotaStore
from app.utils.feedback import apply_corrections, compose_rule_feedback, rule_suggestion_type
from app.utils.lru_cache import TtlLruCache
from app.utils.rule_engine import RuleEngine, select_non_overlapping
from app.utils.text_offsets import cp_to_utf16, utf16_len, utf16_to_cp


@dataclass(frozen=True)
class _Suggestion:
    """Internal suggestion in code-point offsets; `wit` feeds rule feedback and is never serialized."""

    offset: int
    length: int
    original_word: str
    suggested_word: str
    type: SuggestionType
    reason: str
    source: Literal["rule", "ai"]
    wit: str | None = None


def _find_closest(text: str, word: str, hint: int) -> int:
    """Index of `word` in `text` closest to `hint`, or -1. LLM offsets are unreliable, so re-anchor on the text."""
    best = -1
    i = text.find(word)
    while i != -1:
        if best == -1 or abs(i - hint) < abs(best - hint):
            best = i
        i = text.find(word, i + 1)
    return best


def align_ai_suggestions(original: str, mask: MaskResult, ai: list[AiSuggestion]) -> list[_Suggestion]:
    """Converts AI suggestions (masked text, UTF-16) to verified code-point spans in the original text."""
    aligned: list[_Suggestion] = []
    for s in ai:
        if not s.original_word or s.original_word == s.suggested_word:
            continue
        # Never let the model touch redacted PII, and never leak placeholders into suggestions.
        if contains_placeholder(s.original_word) or contains_placeholder(s.suggested_word):
            continue
        hint = mask.to_original_offset(utf16_to_cp(mask.masked, max(0, s.offset)))
        offset = _find_closest(original, s.original_word, hint)
        if offset == -1:  # hallucinated span
            continue
        aligned.append(_Suggestion(offset, len(s.original_word), s.original_word, s.suggested_word, s.type, s.reason, "ai"))
    return aligned


def merge_suggestions(rule: list[_Suggestion], ai: list[_Suggestion]) -> list[_Suggestion]:
    """Rule suggestions are deterministic and win any overlap; AI fills the gaps."""
    accepted = list(rule)
    for s in select_non_overlapping(ai):
        if all(s.offset + s.length <= a.offset or s.offset >= a.offset + a.length for a in accepted):
            accepted.append(s)
    return sorted(accepted, key=lambda s: s.offset)


def _to_wire(text: str, s: _Suggestion) -> Suggestion:
    return Suggestion(
        offset=cp_to_utf16(text, s.offset),
        length=utf16_len(s.original_word),
        original_word=s.original_word,
        suggested_word=s.suggested_word,
        type=s.type,
        reason=s.reason,
        source=s.source,
    )


class GrammarService:
    def __init__(
        self,
        rules: RuleEngine,
        ai: AiNlpService | None,
        quota: QuotaStore,
        *,
        on_ai_error: Callable[[AiUnavailableError], None] | None = None,
        cache: TtlLruCache[str, AiAnalysis] | None = None,
    ) -> None:
        self._rules = rules
        self._ai = ai
        self._quota = quota
        self._on_ai_error = on_ai_error
        self._cache: TtlLruCache[str, AiAnalysis] = cache or TtlLruCache(1000, 600)

    @property
    def ai_available(self) -> bool:
        return self._ai is not None

    async def check(self, user_id: str, text: str, mode: FeedbackMode) -> GrammarCheckResponse:
        rule_suggestions = [
            _Suggestion(c.offset, c.length, c.original_word, c.suggested_word, rule_suggestion_type(c.original_word, c.suggested_word), c.reason, "rule", c.wit)
            for c in self._rules.check(text)
        ]

        quota = await self._quota.get_status(user_id)
        suggestions = rule_suggestions
        ai_wit: str | None = None
        ai_status: AiStatus

        if self._ai is None:
            ai_status = "unavailable"
        elif quota.remaining <= 0:
            ai_status = "quota_exceeded"
        else:
            mask = mask_pii(text)
            try:
                analysis = await self._analyze(mask.masked, mode)
            except AiUnavailableError as err:
                if self._on_ai_error:
                    self._on_ai_error(err)
                ai_status = "unavailable"
            else:
                suggestions = merge_suggestions(rule_suggestions, align_ai_suggestions(text, mask, analysis.suggestions))
                ai_status = "used"
                if analysis.has_error and analysis.wit_feedback:
                    ai_wit = mask.unmask(analysis.wit_feedback)

        has_error = bool(suggestions)
        # Quota is charged only when a 훈수 is actually delivered (AI ran and there is something to fix).
        if ai_status == "used" and has_error:
            quota = await self._quota.consume(user_id)

        return GrammarCheckResponse(
            original_text=text,
            has_error=has_error,
            corrected_text=apply_corrections(text, suggestions),
            wit_feedback=(ai_wit or compose_rule_feedback(suggestions, mode, self._rules.templates)) if has_error else None,
            suggestions=[_to_wire(text, s) for s in suggestions],
            engine="hybrid" if ai_status == "used" else "rule",
            ai_status=ai_status,
            quota=quota,
        )

    async def _analyze(self, masked_text: str, mode: FeedbackMode) -> AiAnalysis:
        assert self._ai is not None
        # Keyed on masked text only: raw PII never enters the cache.
        key = f"{mode}:{masked_text}"
        cached = self._cache.get(key)
        if cached is not None:
            return cached
        analysis = await self._ai.analyze(masked_text, mode)
        self._cache.set(key, analysis)
        return analysis
