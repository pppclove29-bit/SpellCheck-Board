"""Google Play subscription verification.

The client never decides who is PRO: it sends the opaque purchase token, and the server asks Google what that
token actually is. A purchase is only honoured when Google reports an active (or grace-period) subscription whose
product id is one of ours.

Unacknowledged purchases are auto-refunded by Google after three days, so a verified purchase is acknowledged
right away.
"""

import logging
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Literal, Protocol

import httpx

from app.services.google_oauth import ServiceAccountError, ServiceAccountTokenSource

log = logging.getLogger("typeright")

API_BASE = "https://androidpublisher.googleapis.com/androidpublisher/v3"

PRODUCT_MONTHLY = "typeright_pro_monthly"
PRODUCT_YEARLY = "typeright_pro_yearly"
PRO_PRODUCTS = frozenset({PRODUCT_MONTHLY, PRODUCT_YEARLY})

# subscriptionsv2 states that mean "the user paid and should have PRO right now".
ACTIVE_STATES = frozenset({"SUBSCRIPTION_STATE_ACTIVE", "SUBSCRIPTION_STATE_IN_GRACE_PERIOD"})

VerifyOutcome = Literal["active", "inactive", "unknown_product", "not_found", "unavailable"]


@dataclass(frozen=True)
class PlaySubscription:
    outcome: VerifyOutcome
    expires_at: datetime | None = None
    auto_renewing: bool = False
    acknowledged: bool = True

    @property
    def grants_pro(self) -> bool:
        return self.outcome == "active"


class PlayVerifier(Protocol):
    async def verify(self, product_id: str, purchase_token: str) -> PlaySubscription: ...


class DisabledPlayVerifier:
    """Used until the service-account key and package name are configured; never grants PRO."""

    async def verify(self, product_id: str, purchase_token: str) -> PlaySubscription:
        return PlaySubscription(outcome="unavailable")


class GooglePlayVerifier:
    def __init__(self, http: httpx.AsyncClient, tokens: ServiceAccountTokenSource, package_name: str) -> None:
        self._http = http
        self._tokens = tokens
        self._package = package_name

    async def verify(self, product_id: str, purchase_token: str) -> PlaySubscription:
        if product_id not in PRO_PRODUCTS:
            return PlaySubscription(outcome="unknown_product")
        try:
            access_token = await self._tokens.token()
        except ServiceAccountError as exc:
            log.error("Play verification unavailable: %s", exc)
            return PlaySubscription(outcome="unavailable")

        headers = {"Authorization": f"Bearer {access_token}"}
        url = f"{API_BASE}/applications/{self._package}/purchases/subscriptionsv2/tokens/{purchase_token}"
        try:
            res = await self._http.get(url, headers=headers)
        except httpx.HTTPError as exc:
            log.warning("Play verification request failed: %s", exc)
            return PlaySubscription(outcome="unavailable")

        if res.status_code in (404, 410):
            return PlaySubscription(outcome="not_found")
        if res.is_error:
            log.warning("Play verification returned %s", res.status_code)
            return PlaySubscription(outcome="unavailable")

        body = res.json()
        if not self._matches_product(body, product_id):
            return PlaySubscription(outcome="unknown_product")

        state = body.get("subscriptionState")
        expires_at = _parse_rfc3339(_expiry_of(body, product_id))
        acknowledged = body.get("acknowledgementState") == "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED"
        if state not in ACTIVE_STATES:
            return PlaySubscription(outcome="inactive", expires_at=expires_at, acknowledged=acknowledged)

        if not acknowledged:
            # Best effort: a failed acknowledgement must not deny PRO to someone who paid. Play retries the
            # 3-day refund window from purchase time, and /v1/me re-verifies, so the next call tries again.
            acknowledged = await self._acknowledge(product_id, purchase_token, headers)

        auto_renewing = any(
            line.get("autoRenewingPlan", {}).get("autoRenewEnabled") is True
            for line in body.get("lineItems", [])
        )
        return PlaySubscription(
            outcome="active",
            expires_at=expires_at,
            auto_renewing=auto_renewing,
            acknowledged=acknowledged,
        )

    async def _acknowledge(self, product_id: str, purchase_token: str, headers: dict[str, str]) -> bool:
        url = f"{API_BASE}/applications/{self._package}/purchases/subscriptions/{product_id}/tokens/{purchase_token}:acknowledge"
        try:
            res = await self._http.post(url, headers=headers, json={})
        except httpx.HTTPError as exc:
            log.warning("Play acknowledgement failed: %s", exc)
            return False
        if res.is_error:
            log.warning("Play acknowledgement returned %s", res.status_code)
            return False
        return True

    @staticmethod
    def _matches_product(body: dict, product_id: str) -> bool:
        line_items = body.get("lineItems") or []
        if not line_items:
            # Older responses carry no line items; the token was looked up under this product id anyway.
            return True
        return any(item.get("productId") == product_id for item in line_items)


def _expiry_of(body: dict, product_id: str) -> str | None:
    """Latest expiry among the line items for [product_id] (a subscription has one, but be defensive)."""
    times = [
        item.get("expiryTime")
        for item in (body.get("lineItems") or [])
        if item.get("productId") in (product_id, None) and item.get("expiryTime")
    ]
    return max(times) if times else None


def _parse_rfc3339(value: str | None) -> datetime | None:
    if not value:
        return None
    try:
        # Play returns e.g. 2026-10-16T05:00:00.000Z; fromisoformat needs +00:00 on older Pythons.
        return datetime.fromisoformat(value.replace("Z", "+00:00")).astimezone(timezone.utc)
    except ValueError:
        log.warning("Play returned an unparsable expiry time")
        return None
