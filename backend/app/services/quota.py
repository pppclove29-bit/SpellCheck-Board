"""Daily AI 훈수 quota (KST day), PRO entitlement lookup, and AdMob reward ledger."""

import re
from collections.abc import Callable
from datetime import datetime, timedelta, timezone
from typing import Literal, Protocol

import httpx

from app.config import QuotaPolicy
from app.schemas import QuotaStatus

AdRewardResult = Literal["credited", "duplicate", "limit_reached", "invalid_user"]

# Korea has no DST; a fixed offset avoids depending on tzdata being installed on the serverless image.
KST = timezone(timedelta(hours=9))


def kst_date(now: datetime) -> str:
    """YYYY-MM-DD in Asia/Seoul — quota resets at KST midnight."""
    return now.astimezone(KST).date().isoformat()


def _status(is_pro: bool, policy: QuotaPolicy, used: int, bonus: int) -> QuotaStatus:
    limit = policy.pro_daily_limit if is_pro else policy.free_daily_limit
    return QuotaStatus(is_pro=is_pro, limit=limit, used=used, bonus=bonus, remaining=max(0, limit + bonus - used))


class QuotaStore(Protocol):
    async def get_status(self, user_id: str) -> QuotaStatus: ...

    async def consume(self, user_id: str) -> QuotaStatus:
        """Increments today's usage if any is left; returns the updated status."""
        ...

    async def credit_ad_reward(self, user_id: str, transaction_id: str) -> AdRewardResult:
        """Idempotent per AdMob transaction id."""
        ...


class InMemoryQuotaStore:
    """Local dev / tests. State lives in one process only — never use on Vercel."""

    def __init__(self, policy: QuotaPolicy, now: Callable[[], datetime] = lambda: datetime.now(timezone.utc)) -> None:
        self._policy = policy
        self._now = now
        self._usage: dict[tuple[str, str], dict[str, int]] = {}
        self._rewards: dict[str, tuple[str, str]] = {}
        self._pro: set[str] = set()

    def set_pro(self, user_id: str, is_pro: bool) -> None:
        (self._pro.add if is_pro else self._pro.discard)(user_id)

    def forget(self, user_id: str) -> None:
        """Drops all state for a deleted account (mirrors ON DELETE CASCADE)."""
        self._usage = {k: v for k, v in self._usage.items() if k[0] != user_id}
        self._rewards = {t: r for t, r in self._rewards.items() if r[0] != user_id}
        self._pro.discard(user_id)

    async def get_status(self, user_id: str) -> QuotaStatus:
        u = self._today(user_id)
        return _status(user_id in self._pro, self._policy, u["used"], u["bonus"])

    async def consume(self, user_id: str) -> QuotaStatus:
        if (await self.get_status(user_id)).remaining > 0:
            self._today(user_id)["used"] += 1
        return await self.get_status(user_id)

    async def credit_ad_reward(self, user_id: str, transaction_id: str) -> AdRewardResult:
        if transaction_id in self._rewards:
            return "duplicate"
        day = kst_date(self._now())
        if sum(1 for uid, d in self._rewards.values() if uid == user_id and d == day) >= self._policy.max_ad_rewards_per_day:
            return "limit_reached"
        self._rewards[transaction_id] = (user_id, day)
        self._today(user_id)["bonus"] += self._policy.ad_reward_amount
        return "credited"

    def _today(self, user_id: str) -> dict[str, int]:
        return self._usage.setdefault((user_id, kst_date(self._now())), {"used": 0, "bonus": 0})


_UUID = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", re.IGNORECASE)


class SupabaseQuotaStore:
    """Calls the SQL functions in supabase/migrations via PostgREST RPC (atomic, KST day computed in Postgres).

    Requires the service-role key — never ship it to clients.
    """

    def __init__(self, http: httpx.AsyncClient, supabase_url: str, service_role_key: str, policy: QuotaPolicy) -> None:
        self._http = http
        self._rpc_base = f"{supabase_url.rstrip('/')}/rest/v1/rpc"
        self._headers = {"apikey": service_role_key, "Authorization": f"Bearer {service_role_key}"}
        self._policy = policy

    async def get_status(self, user_id: str) -> QuotaStatus:
        return QuotaStatus.model_validate(await self._rpc("ai_quota_status", self._limit_args(user_id)))

    async def consume(self, user_id: str) -> QuotaStatus:
        return QuotaStatus.model_validate(await self._rpc("consume_ai_quota", self._limit_args(user_id)))

    async def credit_ad_reward(self, user_id: str, transaction_id: str) -> AdRewardResult:
        # AdMob passes whatever user id the client set; reject non-UUIDs before they hit the FK.
        if not _UUID.match(user_id):
            return "invalid_user"
        return await self._rpc(
            "credit_ad_reward",
            {
                "p_user_id": user_id,
                "p_transaction_id": transaction_id,
                "p_amount": self._policy.ad_reward_amount,
                "p_max_per_day": self._policy.max_ad_rewards_per_day,
            },
        )

    def _limit_args(self, user_id: str) -> dict[str, object]:
        return {"p_user_id": user_id, "p_free_limit": self._policy.free_daily_limit, "p_pro_limit": self._policy.pro_daily_limit}

    async def _rpc(self, fn: str, args: dict[str, object]):
        res = await self._http.post(f"{self._rpc_base}/{fn}", json=args, headers=self._headers)
        if res.is_error:
            raise RuntimeError(f"Supabase RPC {fn} failed: {res.status_code}")
        return res.json()
