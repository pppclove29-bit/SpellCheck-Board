from datetime import datetime, timezone

import pytest

from app.config import QuotaPolicy
from app.services.quota import InMemoryQuotaStore, kst_date

pytestmark = pytest.mark.anyio
policy = QuotaPolicy(free_daily_limit=5, pro_daily_limit=300, ad_reward_amount=3, max_ad_rewards_per_day=2)


def utc(s: str) -> datetime:
    return datetime.fromisoformat(s).replace(tzinfo=timezone.utc)


def test_kst_date_rolls_over_at_1500_utc() -> None:
    assert kst_date(utc("2026-09-11T14:59:59")) == "2026-09-11"
    assert kst_date(utc("2026-09-11T15:00:00")) == "2026-09-12"


async def test_free_limit_never_exceeded() -> None:
    store = InMemoryQuotaStore(policy)
    for _ in range(7):
        await store.consume("u")
    assert (await store.get_status("u")).model_dump() == {"is_pro": False, "limit": 5, "used": 5, "bonus": 0, "remaining": 0}


async def test_ad_rewards_idempotent_and_capped() -> None:
    store = InMemoryQuotaStore(policy)
    assert await store.credit_ad_reward("u", "t1") == "credited"
    assert await store.credit_ad_reward("u", "t1") == "duplicate"
    assert await store.credit_ad_reward("u", "t2") == "credited"
    assert await store.credit_ad_reward("u", "t3") == "limit_reached"
    status = await store.get_status("u")
    assert (status.bonus, status.remaining) == (6, 11)


async def test_resets_on_new_kst_day() -> None:
    now = utc("2026-09-11T14:00:00")
    store = InMemoryQuotaStore(policy, now=lambda: now)
    await store.consume("u")
    await store.credit_ad_reward("u", "t1")
    now = utc("2026-09-11T15:30:00")
    status = await store.get_status("u")
    assert (status.used, status.bonus, status.remaining) == (0, 0, 5)


async def test_attempt_cap_is_separate_from_quota() -> None:
    store = InMemoryQuotaStore(QuotaPolicy(free_daily_attempts=2, pro_daily_attempts=3))
    assert [await store.try_ai_attempt("u") for _ in range(3)] == [True, True, False]
    store.set_pro("p", True)
    assert [await store.try_ai_attempt("p") for _ in range(4)] == [True, True, True, False]
    assert (await store.get_status("u")).used == 0


async def test_pro_limit() -> None:
    store = InMemoryQuotaStore(policy)
    store.set_pro("p", True)
    status = await store.get_status("p")
    assert (status.is_pro, status.limit, status.remaining) == (True, 300, 300)
