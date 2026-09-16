"""PRO entitlements backed by a verified store purchase.

The purchase token is kept so PRO can be re-verified with Google when it lapses: renewals then pick themselves up
on the next `/v1/me` without Pub/Sub real-time developer notifications.
"""

from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Protocol

import httpx

from app.services.quota import InMemoryQuotaStore


@dataclass(frozen=True)
class PlayPurchaseLink:
    product_id: str
    purchase_token: str
    expires_at: datetime | None


class EntitlementStore(Protocol):
    async def grant_pro(self, user_id: str, expires_at: datetime | None, product_id: str, purchase_token: str) -> None:
        """Upserts the PRO entitlement and remembers which purchase granted it."""
        ...

    async def revoke_pro(self, user_id: str) -> None:
        """Drops PRO back to free, keeping the purchase link so a later renewal can restore it."""
        ...

    async def play_purchase(self, user_id: str) -> PlayPurchaseLink | None:
        """The purchase last verified for this user, or None if they never bought."""
        ...


class InMemoryEntitlementStore:
    """Local dev / tests. Mirrors PRO into the in-memory quota store so `is_pro` stays consistent."""

    def __init__(self, quota: InMemoryQuotaStore) -> None:
        self._quota = quota
        self._purchases: dict[str, PlayPurchaseLink] = {}

    async def grant_pro(self, user_id: str, expires_at: datetime | None, product_id: str, purchase_token: str) -> None:
        self._quota.set_pro(user_id, True)
        self._purchases[user_id] = PlayPurchaseLink(product_id, purchase_token, expires_at)

    async def revoke_pro(self, user_id: str) -> None:
        self._quota.set_pro(user_id, False)

    async def play_purchase(self, user_id: str) -> PlayPurchaseLink | None:
        return self._purchases.get(user_id)


class SupabaseEntitlementStore:
    """Calls the SQL functions in supabase/migrations via PostgREST RPC. Service-role key only."""

    def __init__(self, http: httpx.AsyncClient, supabase_url: str, service_role_key: str) -> None:
        self._http = http
        self._rpc_base = f"{supabase_url.rstrip('/')}/rest/v1/rpc"
        self._headers = {"apikey": service_role_key, "Authorization": f"Bearer {service_role_key}"}

    async def grant_pro(self, user_id: str, expires_at: datetime | None, product_id: str, purchase_token: str) -> None:
        await self._rpc(
            "grant_play_pro",
            {
                "p_user_id": user_id,
                "p_expires_at": expires_at.isoformat() if expires_at else None,
                "p_product_id": product_id,
                "p_purchase_token": purchase_token,
            },
        )

    async def revoke_pro(self, user_id: str) -> None:
        await self._rpc("revoke_pro", {"p_user_id": user_id})

    async def play_purchase(self, user_id: str) -> PlayPurchaseLink | None:
        row = await self._rpc("play_purchase", {"p_user_id": user_id})
        if not row or not row.get("purchase_token"):
            return None
        expires = row.get("pro_expires_at")
        return PlayPurchaseLink(
            product_id=row.get("product_id") or "",
            purchase_token=row["purchase_token"],
            expires_at=datetime.fromisoformat(expires.replace("Z", "+00:00")).astimezone(timezone.utc) if expires else None,
        )

    async def _rpc(self, fn: str, args: dict[str, object]):
        res = await self._http.post(f"{self._rpc_base}/{fn}", json=args, headers=self._headers)
        if res.is_error:
            raise RuntimeError(f"Supabase RPC {fn} failed: {res.status_code}")
        return res.json()
