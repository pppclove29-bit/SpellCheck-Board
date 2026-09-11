"""월 AI 예산 가드: 경고 기준($100) 초과 시 웹훅 알림, 상한($200) 도달 시 AI 호출 자동 중단.

Month = UTC calendar month (OpenAI bills per UTC month). Cost is estimated from token usage at list
price, ignoring prompt-cache discounts, so it errs on the high side.
"""

import logging
import time
from collections.abc import Awaitable, Callable
from datetime import datetime, timezone
from typing import Literal, Protocol

import httpx

from app.config import BudgetPolicy

log = logging.getLogger("typeright")

PAUSED_NOTICE = "오늘 AI 선생님이 퇴근했습니다 😴"

AlertLevel = Literal["warn", "cap"]
Alerter = Callable[[str], Awaitable[None]]


def utc_month(now: datetime) -> str:
    return now.astimezone(timezone.utc).strftime("%Y-%m")


class SpendStore(Protocol):
    async def month_total(self, month: str) -> float: ...

    async def add(self, month: str, cost_usd: float) -> float:
        """Atomically adds and returns the new monthly total."""
        ...

    async def claim_alert(self, month: str, level: AlertLevel) -> bool:
        """True only for the first caller per (month, level), so each alert is sent once."""
        ...


class InMemorySpendStore:
    """Local dev / tests only."""

    def __init__(self) -> None:
        self._totals: dict[str, float] = {}
        self._alerts: set[tuple[str, str]] = set()

    async def month_total(self, month: str) -> float:
        return self._totals.get(month, 0.0)

    async def add(self, month: str, cost_usd: float) -> float:
        self._totals[month] = self._totals.get(month, 0.0) + cost_usd
        return self._totals[month]

    async def claim_alert(self, month: str, level: AlertLevel) -> bool:
        if (month, level) in self._alerts:
            return False
        self._alerts.add((month, level))
        return True


class SupabaseSpendStore:
    """SQL functions in supabase/migrations/*_ai_budget.sql via PostgREST RPC (service-role key)."""

    def __init__(self, http: httpx.AsyncClient, supabase_url: str, service_role_key: str) -> None:
        self._http = http
        self._rpc_base = f"{supabase_url.rstrip('/')}/rest/v1/rpc"
        self._headers = {"apikey": service_role_key, "Authorization": f"Bearer {service_role_key}"}

    async def month_total(self, month: str) -> float:
        return float(await self._rpc("ai_spend_total", {"p_month": month}))

    async def add(self, month: str, cost_usd: float) -> float:
        return float(await self._rpc("add_ai_spend", {"p_month": month, "p_cost": cost_usd}))

    async def claim_alert(self, month: str, level: AlertLevel) -> bool:
        return bool(await self._rpc("claim_budget_alert", {"p_month": month, "p_level": level}))

    async def _rpc(self, fn: str, args: dict[str, object]):
        res = await self._http.post(f"{self._rpc_base}/{fn}", json=args, headers=self._headers)
        if res.is_error:
            raise RuntimeError(f"Supabase RPC {fn} failed: {res.status_code}")
        return res.json()


class WebhookAlerter:
    """Posts to a Slack ("text") or Discord ("content") incoming webhook. Never raises."""

    def __init__(self, http: httpx.AsyncClient, url: str) -> None:
        self._http = http
        self._url = url

    async def __call__(self, message: str) -> None:
        try:
            res = await self._http.post(self._url, json={"text": message, "content": message})
            if res.is_error:
                log.warning("budget alert webhook returned %s", res.status_code)
        except httpx.HTTPError as err:
            log.warning("budget alert webhook failed: %s", err)


class BudgetGuard:
    def __init__(
        self,
        store: SpendStore,
        policy: BudgetPolicy,
        alert: Alerter | None = None,
        *,
        now: Callable[[], datetime] = lambda: datetime.now(timezone.utc),
        clock: Callable[[], float] = time.monotonic,
        cache_ttl_s: float = 30.0,
    ) -> None:
        self._store = store
        self._policy = policy
        self._alert = alert
        self._now = now
        self._clock = clock
        self._cache_ttl_s = cache_ttl_s
        self._cached: tuple[str, float, float] | None = None  # (month, total, expires_at)

    def cost_usd(self, prompt_tokens: int, completion_tokens: int) -> float:
        p = self._policy
        return prompt_tokens / 1_000_000 * p.price_input_per_m + completion_tokens / 1_000_000 * p.price_output_per_m

    async def is_paused(self) -> bool:
        return await self._month_total() >= self._policy.cap_usd

    async def record(self, prompt_tokens: int, completion_tokens: int) -> None:
        cost = self.cost_usd(prompt_tokens, completion_tokens)
        if cost <= 0:
            return
        month = utc_month(self._now())
        total = await self._store.add(month, cost)
        self._cached = (month, total, self._clock() + self._cache_ttl_s)

        if total >= self._policy.warn_usd and await self._store.claim_alert(month, "warn"):
            await self._send(f"[TypeRight] {month} OpenAI 사용액 ${total:.2f} — 경고 기준 ${self._policy.warn_usd:.0f} 초과")
        if total >= self._policy.cap_usd and await self._store.claim_alert(month, "cap"):
            await self._send(
                f"[TypeRight] {month} OpenAI 사용액 ${total:.2f} — 상한 ${self._policy.cap_usd:.0f} 도달. "
                "AI 호출 자동 중단, 규칙 교정만 제공 중"
            )

    async def _month_total(self) -> float:
        # Cached per warm instance so every keystroke-triggered request doesn't add a DB round trip.
        month = utc_month(self._now())
        cached = self._cached
        if cached and cached[0] == month and cached[2] > self._clock():
            return cached[1]
        total = await self._store.month_total(month)
        self._cached = (month, total, self._clock() + self._cache_ttl_s)
        return total

    async def _send(self, message: str) -> None:
        log.warning(message)
        if self._alert:
            await self._alert(message)
