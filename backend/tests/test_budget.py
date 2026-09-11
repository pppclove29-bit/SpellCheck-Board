from datetime import datetime, timezone

import httpx
import pytest

from app.config import BudgetPolicy
from app.services.budget import BudgetGuard, InMemorySpendStore, WebhookAlerter, utc_month

pytestmark = pytest.mark.anyio

# $1 per 1M input tokens, $2 per 1M output tokens: easy mental math.
POLICY = BudgetPolicy(warn_usd=1.0, cap_usd=2.0, price_input_per_m=1.0, price_output_per_m=2.0)
SEPT = datetime(2026, 9, 11, tzinfo=timezone.utc)


class Clock:
    def __init__(self) -> None:
        self.t = 0.0

    def __call__(self) -> float:
        return self.t


def guard(store: InMemorySpendStore, alerts: list[str] | None = None, now=lambda: SEPT, clock=None) -> BudgetGuard:
    async def alert(message: str) -> None:
        if alerts is not None:
            alerts.append(message)

    return BudgetGuard(store, POLICY, alert, now=now, clock=clock or Clock())


def test_utc_month() -> None:
    assert utc_month(datetime(2026, 9, 30, 23, 59, tzinfo=timezone.utc)) == "2026-09"


def test_cost_estimate() -> None:
    assert guard(InMemorySpendStore()).cost_usd(1_000_000, 500_000) == pytest.approx(2.0)


async def test_warns_once_then_pauses_and_alerts_once_at_cap() -> None:
    store, alerts = InMemorySpendStore(), []
    g = guard(store, alerts)

    await g.record(900_000, 0)  # $0.90
    assert (await g.is_paused(), alerts) == (False, [])

    await g.record(200_000, 0)  # $1.10 → warn
    await g.record(100_000, 0)  # $1.20 → no second warn
    assert len(alerts) == 1 and "경고 기준 $1" in alerts[0]
    assert not await g.is_paused()

    await g.record(0, 500_000)  # +$1.00 → $2.20 ≥ cap
    await g.record(0, 1)
    assert await g.is_paused()
    assert len(alerts) == 2 and "AI 호출 자동 중단" in alerts[1]


async def test_new_month_resumes() -> None:
    store = InMemorySpendStore()
    now = SEPT
    g = guard(store, now=lambda: now)
    await g.record(0, 1_000_000)  # $2.00 in September
    assert await g.is_paused()
    now = datetime(2026, 10, 1, tzinfo=timezone.utc)
    assert not await g.is_paused()


async def test_total_is_cached_between_db_reads() -> None:
    store, clock = InMemorySpendStore(), Clock()
    g = guard(store, clock=clock)
    assert not await g.is_paused()  # caches $0 for 30s
    await store.add("2026-09", 5.0)  # another instance spent past the cap
    assert not await g.is_paused()
    clock.t = 31
    assert await g.is_paused()


async def test_ignores_zero_usage() -> None:
    store = InMemorySpendStore()
    await guard(store).record(0, 0)
    assert await store.month_total("2026-09") == 0


async def test_webhook_alerter_posts_slack_and_discord_keys_and_never_raises() -> None:
    seen: list[httpx.Request] = []

    def ok(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(200)

    await WebhookAlerter(httpx.AsyncClient(transport=httpx.MockTransport(ok)), "https://hooks.example/x")("hi")
    assert seen[0].read() == b'{"text":"hi","content":"hi"}'

    def down(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down", request=request)

    await WebhookAlerter(httpx.AsyncClient(transport=httpx.MockTransport(down)), "https://hooks.example/x")("hi")
