import logging
import re
from collections.abc import Callable
from dataclasses import dataclass
from typing import Literal

from app.schemas import AiStatus, FeedbackMode, GrammarCheckResponse, Suggestion, SuggestionType
from app.services.ai_nlp import AiAnalysis, AiNlpService, AiSuggestion, AiUnavailableError
from app.services.anonymizer import MaskResult, contains_placeholder, mask_pii
from app.services.budget import PAUSED_NOTICE, BudgetGuard
from app.services.quota import QuotaStore
from app.utils.feedback import apply_corrections, compose_rule_feedback, rule_suggestion_type
from app.utils.lru_cache import TtlLruCache
from app.utils.rule_engine import RuleEngine, select_non_overlapping
from app.utils.text_offsets import cp_to_utf16, utf16_len, utf16_to_cp

log = logging.getLogger("typeright")

_HANGUL_SYLLABLE = re.compile(r"[가-힣]")
# Texts with fewer Hangul syllables (ㅋㅋㅋ, emoji, numbers, Latin) have nothing for a Korean grammar model to do.
MIN_HANGUL_SYLLABLES_FOR_AI = 2
WIT_MAX_CHARS = 120


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


def _clamp(text: str, max_chars: int) -> str:
    return text if len(text) <= max_chars else text[: max_chars - 1] + "…"


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
        budget: BudgetGuard | None = None,
        ai_max_input_chars: int = 150,
        on_ai_error: Callable[[AiUnavailableError], None] | None = None,
        cache: TtlLruCache[str, AiAnalysis] | None = None,
    ) -> None:
        self._rules = rules
        self._ai = ai
        self._quota = quota
        self._budget = budget
        self._ai_max_input_chars = ai_max_input_chars
        self._on_ai_error = on_ai_error
        self._cache: TtlLruCache[str, AiAnalysis] = cache or TtlLruCache(1000, 600)

    @property
    def ai_available(self) -> bool:
        return self._ai is not None

    async def ai_paused(self) -> bool:
        """True when the monthly budget cap has been reached (AI configured but switched off)."""
        return self._ai is not None and self._budget is not None and await self._budget.is_paused()

    def ai_eligible(self, text: str) -> bool:
        """Only short Korean sentences go to the AI; everything else gets rule-only checks (cost/abuse bound)."""
        return len(text) <= self._ai_max_input_chars and len(_HANGUL_SYLLABLE.findall(text)) >= MIN_HANGUL_SYLLABLES_FOR_AI

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
        elif not self.ai_eligible(text):
            ai_status = "skipped"
        elif await self.ai_paused():
            ai_status = "paused"
        elif quota.remaining <= 0:
            ai_status = "quota_exceeded"
        else:
            mask = mask_pii(text)
            key = f"{mode}:{mask.masked}"  # masked text only: raw PII never enters the cache
            analysis = self._cache.get(key)
            # Cache hits are free; only real model calls count toward the daily attempt cap.
            if analysis is None and not await self._quota.try_ai_attempt(user_id):
                ai_status = "rate_limited"
            else:
                try:
                    if analysis is None:
                        analysis = await self._call_ai(key, mask.masked, mode)
                except AiUnavailableError as err:
                    await self._record_spend(err.prompt_tokens, err.completion_tokens)
                    if self._on_ai_error:
                        self._on_ai_error(err)
                    ai_status = "unavailable"
                else:
                    suggestions = merge_suggestions(rule_suggestions, align_ai_suggestions(text, mask, analysis.suggestions))
                    ai_status = "used"
                    if analysis.has_error and analysis.wit_feedback:
                        ai_wit = _clamp(mask.unmask(analysis.wit_feedback), WIT_MAX_CHARS)

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
            ai_notice=PAUSED_NOTICE if ai_status == "paused" else None,
            quota=quota,
        )

    async def _call_ai(self, key: str, masked_text: str, mode: FeedbackMode) -> AiAnalysis:
        assert self._ai is not None
        result = await self._ai.analyze(masked_text, mode)
        await self._record_spend(result.prompt_tokens, result.completion_tokens)
        self._cache.set(key, result.analysis)
        return result.analysis

    async def _record_spend(self, prompt_tokens: int, completion_tokens: int) -> None:
        if self._budget is None or (prompt_tokens == 0 and completion_tokens == 0):
            return
        try:
            await self._budget.record(prompt_tokens, completion_tokens)
        except Exception:
            # The user already got (or is getting) a response; a bookkeeping failure must not turn it into a 500.
            log.warning("failed to record AI spend", exc_info=True)
