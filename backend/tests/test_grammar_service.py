from collections.abc import Callable
from dataclasses import replace

import pytest

from datetime import datetime, timezone

from app.config import BudgetPolicy
from app.schemas import FeedbackMode
from app.services.ai_nlp import AiAnalysis, AiResult, AiSuggestion, AiUnavailableError
from app.services.budget import BudgetGuard, InMemorySpendStore, utc_month
from app.services.grammar_service import GrammarService
from app.services.quota import InMemoryQuotaStore
from app.utils.rule_engine import RuleEngine
from tests.conftest import POLICY

pytestmark = pytest.mark.anyio
rules = RuleEngine()


class FakeAi:
    def __init__(self, respond: Callable[[str], AiAnalysis]) -> None:
        self.calls: list[tuple[str, FeedbackMode]] = []
        self._respond = respond

    async def analyze(self, masked_text: str, mode: FeedbackMode) -> AiResult:
        self.calls.append((masked_text, mode))
        return AiResult(self._respond(masked_text), prompt_tokens=1000, completion_tokens=500)


def analysis(suggestions: list[AiSuggestion] | None = None, wit: str | None = None, has_error: bool | None = None) -> AiAnalysis:
    suggestions = suggestions or []
    return AiAnalysis(
        has_error=bool(suggestions) if has_error is None else has_error,
        wit_feedback=wit,
        suggestions=suggestions,
    )


def sugg(original: str, suggested: str, offset: int = 0, type_: str = "spelling") -> AiSuggestion:
    return AiSuggestion(offset=offset, length=len(original), original_word=original, suggested_word=suggested, type=type_, reason="r")


def setup(ai=None, on_ai_error=None) -> tuple[GrammarService, InMemoryQuotaStore]:
    quota = InMemoryQuotaStore(POLICY)
    return GrammarService(rules, ai, quota, on_ai_error=on_ai_error), quota


async def test_rule_only_v4_shape() -> None:
    service, _ = setup()
    res = await service.check("u", "오늘 진짜 어의가 없네", "spicy_wit")
    assert res.model_dump() == {
        "original_text": "오늘 진짜 어의가 없네",
        "has_error": True,
        "corrected_text": "오늘 진짜 어이가 없네",
        "wit_feedback": "어의는 조선시대 궁궐 의사입니다. '어처구니'가 없으신 거죠? 🩺",
        "suggestions": [
            {
                "offset": 6,
                "length": 2,
                "original_word": "어의",
                "suggested_word": "어이",
                "type": "spelling",
                "reason": "'어이없다'가 올바른 표기입니다.",
                "source": "rule",
            }
        ],
        "engine": "rule",
        "ai_status": "unavailable",
        "ai_notice": None,
        "quota": {"is_pro": False, "limit": 5, "used": 0, "bonus": 0, "remaining": 5},
    }


async def test_mode_templates_for_police_and_gentle() -> None:
    service, _ = setup()
    police = await service.check("u", "나도 할수있어", "police")
    assert police.wit_feedback == "🚨 맞춤법 위반 적발! '할수있' → '할 수 있' 교정 전까지 통과 불가!"
    assert police.suggestions[0].type == "spacing"
    gentle = await service.check("u", "나도 할수있어", "gentle")
    assert gentle.wit_feedback == "✏️ '할수있' → '할 수 있' : 의존 명사 '수'는 앞뒤 말과 띄어 씁니다."


async def test_offsets_are_utf16_after_emoji() -> None:
    service, _ = setup()
    res = await service.check("u", "😀 몇일 남았어?", "gentle")
    assert (res.suggestions[0].offset, res.suggestions[0].length) == (3, 2)
    assert res.corrected_text == "😀 며칠 남았어?"


async def test_clean_text_has_null_feedback_and_no_charge() -> None:
    service, _ = setup(FakeAi(lambda _: analysis()))
    res = await service.check("u", "맞춤법이 정확합니다.", "spicy_wit")
    assert (res.has_error, res.wit_feedback, res.ai_status, res.quota.used) == (False, None, "used", 0)


async def test_hybrid_masks_realigns_filters_and_charges_once() -> None:
    text = "몇일 뒤 010-1234-5678로 연락주세요 날씨가 좋와요"
    ai = FakeAi(
        lambda _: analysis(
            [
                sugg("몇일", "몇 일"),
                sugg("좋와요", "좋아요", offset=99, type_="grammar"),
                sugg("[PHONE_1]로", "[PHONE_1]으로", offset=5, type_="grammar"),
                sugg("없는말", "있는말", offset=1),
            ],
            wit="[PHONE_1] 말고 '좋와요'가 문제예요 😏",
        )
    )
    service, _ = setup(ai)

    res = await service.check("u", text, "spicy_wit")

    masked, mode = ai.calls[0]
    assert "[PHONE_1]" in masked and "010-1234-5678" not in masked and mode == "spicy_wit"
    assert (res.engine, res.ai_status) == ("hybrid", "used")
    assert [(s.offset, s.suggested_word, s.source, s.type) for s in res.suggestions] == [
        (0, "며칠", "rule", "spelling"),
        (text.index("좋와요"), "좋아요", "ai", "grammar"),
    ]
    assert res.corrected_text == "며칠 뒤 010-1234-5678로 연락주세요 날씨가 좋아요"
    assert res.wit_feedback == "010-1234-5678 말고 '좋와요'가 문제예요 😏"
    assert (res.quota.used, res.quota.remaining) == (1, 4)


async def test_rule_feedback_when_ai_finds_nothing() -> None:
    service, _ = setup(FakeAi(lambda _: analysis()))
    res = await service.check("u", "오늘 몇일이야?", "spicy_wit")
    assert res.wit_feedback == "'몇일'은 달력에 없는 날이에요. 며칠을 찾아도 안 나올걸요? 📅"
    assert res.quota.used == 1


async def test_no_charge_when_ai_findings_are_discarded() -> None:
    service, _ = setup(FakeAi(lambda _: analysis([sugg("없는말", "x")], wit="헛소리")))
    res = await service.check("u", "안녕하세요", "spicy_wit")
    assert (res.has_error, res.wit_feedback, res.quota.used) == (False, None, 0)


async def test_repeated_word_anchors_to_nearest_occurrence() -> None:
    text = "좋와요 그리고 좋와요"
    second = text.rindex("좋와요")
    service, _ = setup(FakeAi(lambda _: analysis([sugg("좋와요", "좋아요", offset=second + 1)])))
    res = await service.check("u", text, "gentle")
    assert [s.offset for s in res.suggestions] == [second]


async def test_quota_exhausted_skips_ai() -> None:
    ai = FakeAi(lambda _: analysis())
    service, quota = setup(ai)
    for _ in range(5):
        await quota.consume("u")
    res = await service.check("u", "내일 갈께", "spicy_wit")
    assert ai.calls == []
    assert (res.ai_status, res.engine, res.has_error, res.quota.remaining) == ("quota_exceeded", "rule", True, 0)


async def test_pro_gets_fair_use_limit() -> None:
    service, quota = setup(FakeAi(lambda _: analysis()))
    quota.set_pro("p", True)
    res = await service.check("p", "안녕", "police")
    assert (res.quota.is_pro, res.quota.limit) == (True, 300)


async def test_ai_unavailable_degrades_without_charge() -> None:
    errors: list[AiUnavailableError] = []

    def fail(_: str) -> AiAnalysis:
        raise AiUnavailableError("timeout")

    service, _ = setup(FakeAi(fail), on_ai_error=errors.append)
    res = await service.check("u", "내일 갈께", "spicy_wit")
    assert (res.engine, res.ai_status, res.has_error, res.quota.used) == ("rule", "unavailable", True, 0)
    assert len(errors) == 1


async def test_unexpected_errors_propagate() -> None:
    def boom(_: str) -> AiAnalysis:
        raise TypeError("bug")

    service, _ = setup(FakeAi(boom))
    with pytest.raises(TypeError):
        await service.check("u", "안녕", "spicy_wit")


PRICED = BudgetPolicy(warn_usd=100, cap_usd=200, price_input_per_m=1.0, price_output_per_m=2.0)
THIS_MONTH = utc_month(datetime.now(timezone.utc))


async def test_budget_cap_pauses_ai_with_notice_and_no_charge() -> None:
    spend = InMemorySpendStore()
    await spend.add(THIS_MONTH, 200.0)
    ai = FakeAi(lambda _: analysis())
    service = GrammarService(rules, ai, InMemoryQuotaStore(POLICY), budget=BudgetGuard(spend, PRICED))

    res = await service.check("u", "내일 갈께", "spicy_wit")

    assert ai.calls == []
    assert (res.ai_status, res.engine, res.ai_notice) == ("paused", "rule", "오늘 AI 선생님이 퇴근했습니다 😴")
    assert res.has_error and res.suggestions[0].suggested_word == "갈게"
    assert res.quota.used == 0
    assert await service.ai_paused()


async def test_fresh_ai_calls_record_spend_but_cache_hits_do_not() -> None:
    spend = InMemorySpendStore()
    service = GrammarService(rules, FakeAi(lambda _: analysis()), InMemoryQuotaStore(POLICY), budget=BudgetGuard(spend, PRICED))
    await service.check("u", "안녕하세요", "spicy_wit")
    await service.check("u", "안녕하세요", "spicy_wit")
    assert await spend.month_total(THIS_MONTH) == pytest.approx(1000 / 1e6 * 1.0 + 500 / 1e6 * 2.0)


async def test_failed_ai_calls_still_count_billed_tokens() -> None:
    def refused(_: str) -> AiAnalysis:
        raise AiUnavailableError("refused", prompt_tokens=2000, completion_tokens=0)

    spend = InMemorySpendStore()
    service = GrammarService(rules, FakeAi(refused), InMemoryQuotaStore(POLICY), budget=BudgetGuard(spend, PRICED))
    res = await service.check("u", "안녕", "gentle")
    assert res.ai_status == "unavailable"
    assert await spend.month_total(THIS_MONTH) == pytest.approx(0.002)


@pytest.mark.parametrize("text", ["가" * 151, "ㅋㅋㅋㅋ", "ok 123", "안", "😀😀"], ids=["too-long", "jamo", "latin-digits", "one-syllable", "emoji"])
async def test_ineligible_text_skips_ai(text: str) -> None:
    ai = FakeAi(lambda _: analysis())
    service, quota = setup(ai)
    res = await service.check("u", text, "spicy_wit")
    assert (res.ai_status, res.engine, ai.calls) == ("skipped", "rule", [])
    assert (await quota.get_status("u")).used == 0


async def test_long_text_still_gets_rule_corrections() -> None:
    service, _ = setup(FakeAi(lambda _: analysis()))
    res = await service.check("u", "몇일" + "가" * 150, "gentle")
    assert res.ai_status == "skipped" and res.suggestions[0].suggested_word == "며칠"


async def test_attempt_cap_rate_limits_but_cache_hits_are_free() -> None:
    ai = FakeAi(lambda _: analysis())
    quota = InMemoryQuotaStore(replace(POLICY, free_daily_attempts=2))
    service = GrammarService(rules, ai, quota)
    assert (await service.check("u", "안녕하세요", "spicy_wit")).ai_status == "used"
    assert (await service.check("u", "반갑습니다", "spicy_wit")).ai_status == "used"
    assert (await service.check("u", "안녕하세요", "spicy_wit")).ai_status == "used"  # cache hit, no attempt
    res = await service.check("u", "고맙습니다", "spicy_wit")
    assert (res.ai_status, res.engine, len(ai.calls)) == ("rate_limited", "rule", 2)


async def test_ai_wit_is_clamped() -> None:
    service, _ = setup(FakeAi(lambda _: analysis([sugg("좋와요", "좋아요")], wit="가" * 300)))
    res = await service.check("u", "좋와요", "spicy_wit")
    assert len(res.wit_feedback) == 120 and res.wit_feedback.endswith("…")


async def test_ai_results_cached_per_mode_and_masked_text() -> None:
    ai = FakeAi(lambda _: analysis())
    service, _ = setup(ai)
    await service.check("u", "안녕하세요", "spicy_wit")
    await service.check("u", "안녕하세요", "spicy_wit")
    await service.check("u", "안녕하세요", "gentle")
    assert len(ai.calls) == 2
