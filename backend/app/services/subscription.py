"""Turns a verified Play purchase into a PRO entitlement, and keeps it in step with renewals.

Renewals are picked up lazily instead of through Pub/Sub notifications: `/v1/me` calls [refresh], which asks
Google again only once the stored expiry has passed. A renewed subscription therefore restores PRO on the user's
next app open, and a cancelled one drops to free at the same moment.
"""

import logging
from datetime import datetime, timezone
from typing import Literal

from app.services.entitlements import EntitlementStore
from app.services.play_billing import PlayVerifier

log = logging.getLogger("typeright")

VerifyResult = Literal["activated", "inactive", "unknown_product", "not_found", "unavailable", "already_claimed"]


class PlaySubscriptionService:
    def __init__(
        self,
        verifier: PlayVerifier,
        entitlements: EntitlementStore,
        now=lambda: datetime.now(timezone.utc),
    ) -> None:
        self._verifier = verifier
        self._entitlements = entitlements
        self._now = now

    async def verify_purchase(self, user_id: str, product_id: str, purchase_token: str) -> VerifyResult:
        subscription = await self._verifier.verify(product_id, purchase_token)
        if not subscription.grants_pro:
            if subscription.outcome == "inactive":
                await self._entitlements.revoke_pro(user_id)
            return subscription.outcome  # type: ignore[return-value]
        try:
            await self._entitlements.grant_pro(user_id, subscription.expires_at, product_id, purchase_token)
        except Exception as exc:
            # grant_play_pro rejects a token already bound to a different account (receipt sharing).
            log.warning("PRO grant rejected: %s", exc)
            return "already_claimed"
        return "activated"

    async def refresh(self, user_id: str) -> None:
        """Re-verifies a lapsed subscription. Never raises: /v1/me must work even when Play is down."""
        try:
            link = await self._entitlements.play_purchase(user_id)
            if link is None:
                return
            if link.expires_at is not None and link.expires_at > self._now():
                return  # still inside the paid period; nothing to ask Google about
            await self.verify_purchase(user_id, link.product_id, link.purchase_token)
        except Exception as exc:
            log.warning("subscription refresh failed: %s", exc)
