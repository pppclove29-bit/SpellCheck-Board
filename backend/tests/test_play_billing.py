"""Play subscription verification: what Google says decides PRO, never the client."""

from datetime import datetime, timedelta, timezone

import httpx
import pytest

from app.services.entitlements import InMemoryEntitlementStore
from app.services.google_oauth import ServiceAccountError, ServiceAccountKey, ServiceAccountTokenSource
from app.services.play_billing import (
    PRODUCT_MONTHLY,
    DisabledPlayVerifier,
    GooglePlayVerifier,
    PlaySubscription,
)
from app.services.quota import InMemoryQuotaStore
from app.services.subscription import PlaySubscriptionService
from tests.conftest import POLICY

pytestmark = pytest.mark.anyio

TOKEN = "purchase-token-abc"
NOW = datetime(2026, 9, 16, tzinfo=timezone.utc)
FUTURE = (NOW + timedelta(days=30)).isoformat().replace("+00:00", "Z")


def active_body(product_id: str = PRODUCT_MONTHLY, acknowledged: bool = True, state: str = "SUBSCRIPTION_STATE_ACTIVE") -> dict:
    return {
        "subscriptionState": state,
        "acknowledgementState": (
            "ACKNOWLEDGEMENT_STATE_ACKNOWLEDGED" if acknowledged else "ACKNOWLEDGEMENT_STATE_PENDING"
        ),
        "lineItems": [{"productId": product_id, "expiryTime": FUTURE, "autoRenewingPlan": {"autoRenewEnabled": True}}],
    }


class FakeTokens:
    """Stands in for ServiceAccountTokenSource."""

    def __init__(self, token: str | None = "access-token") -> None:
        self._token = token

    async def token(self) -> str:
        if self._token is None:
            raise ServiceAccountError("no key")
        return self._token


def verifier(handler, token: str | None = "access-token") -> GooglePlayVerifier:
    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    return GooglePlayVerifier(http, FakeTokens(token), "com.typeright.app")


# --- GooglePlayVerifier ---------------------------------------------------------------------------


async def test_active_subscription_grants_pro_and_reports_expiry():
    result = await verifier(lambda request: httpx.Response(200, json=active_body())).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.grants_pro
    assert result.expires_at == datetime.fromisoformat(FUTURE.replace("Z", "+00:00"))
    assert result.auto_renewing is True


async def test_grace_period_still_grants_pro():
    body = active_body(state="SUBSCRIPTION_STATE_IN_GRACE_PERIOD")
    result = await verifier(lambda request: httpx.Response(200, json=body)).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.grants_pro


async def test_expired_subscription_does_not_grant_pro():
    body = active_body(state="SUBSCRIPTION_STATE_EXPIRED")
    result = await verifier(lambda request: httpx.Response(200, json=body)).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "inactive"
    assert not result.grants_pro


async def test_unverified_purchase_is_acknowledged_so_play_does_not_auto_refund():
    calls: list[str] = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append(f"{request.method} {request.url.path}")
        if request.method == "POST":
            return httpx.Response(200, json={})
        return httpx.Response(200, json=active_body(acknowledged=False))

    result = await verifier(handler).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.grants_pro
    assert result.acknowledged is True
    assert any(call.endswith(":acknowledge") for call in calls)


async def test_a_failed_acknowledgement_still_grants_pro():
    def handler(request: httpx.Request) -> httpx.Response:
        if request.method == "POST":
            return httpx.Response(500)
        return httpx.Response(200, json=active_body(acknowledged=False))

    result = await verifier(handler).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.grants_pro
    assert result.acknowledged is False


async def test_a_token_for_a_different_product_is_rejected():
    body = active_body(product_id="some_other_subscription")
    result = await verifier(lambda request: httpx.Response(200, json=body)).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "unknown_product"


async def test_a_product_we_do_not_sell_never_reaches_google():
    called = False

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal called
        called = True
        return httpx.Response(200, json=active_body())

    result = await verifier(handler).verify("gold_coins_9999", TOKEN)

    assert result.outcome == "unknown_product"
    assert called is False


async def test_an_unknown_token_is_not_found():
    result = await verifier(lambda request: httpx.Response(404)).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "not_found"


async def test_google_errors_are_reported_as_unavailable_not_as_denial():
    result = await verifier(lambda request: httpx.Response(503)).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "unavailable"


async def test_a_network_failure_is_unavailable():
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("boom")

    result = await verifier(handler).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "unavailable"


async def test_a_broken_service_account_key_is_unavailable():
    result = await verifier(lambda request: httpx.Response(200, json=active_body()), token=None).verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "unavailable"


async def test_disabled_verifier_never_grants_pro():
    result = await DisabledPlayVerifier().verify(PRODUCT_MONTHLY, TOKEN)

    assert result.outcome == "unavailable"
    assert not result.grants_pro


# --- PlaySubscriptionService ----------------------------------------------------------------------


class StubVerifier:
    def __init__(self, *results: PlaySubscription) -> None:
        self._results = list(results)
        self.calls: list[tuple[str, str]] = []

    async def verify(self, product_id: str, purchase_token: str) -> PlaySubscription:
        self.calls.append((product_id, purchase_token))
        return self._results.pop(0) if len(self._results) > 1 else self._results[0]


def service(*results: PlaySubscription, now: datetime = NOW):
    quota = InMemoryQuotaStore(POLICY)
    entitlements = InMemoryEntitlementStore(quota)
    stub = StubVerifier(*results)
    return PlaySubscriptionService(stub, entitlements, now=lambda: now), quota, entitlements, stub


async def test_a_verified_purchase_activates_pro():
    svc, quota, entitlements, _ = service(PlaySubscription("active", expires_at=NOW + timedelta(days=30)))

    assert await svc.verify_purchase("user-1", PRODUCT_MONTHLY, TOKEN) == "activated"
    assert (await quota.get_status("user-1")).is_pro
    assert (await entitlements.play_purchase("user-1")).purchase_token == TOKEN


async def test_an_inactive_purchase_revokes_pro():
    svc, quota, entitlements, _ = service(
        PlaySubscription("active", expires_at=NOW + timedelta(days=30)),
        PlaySubscription("inactive"),
    )
    await svc.verify_purchase("user-1", PRODUCT_MONTHLY, TOKEN)

    assert await svc.verify_purchase("user-1", PRODUCT_MONTHLY, TOKEN) == "inactive"
    assert not (await quota.get_status("user-1")).is_pro


async def test_an_unreachable_play_leaves_pro_untouched():
    svc, quota, _, _ = service(
        PlaySubscription("active", expires_at=NOW + timedelta(days=30)),
        PlaySubscription("unavailable"),
    )
    await svc.verify_purchase("user-1", PRODUCT_MONTHLY, TOKEN)

    assert await svc.verify_purchase("user-1", PRODUCT_MONTHLY, TOKEN) == "unavailable"
    assert (await quota.get_status("user-1")).is_pro, "a Play outage must not cancel a paying user's PRO"


async def test_a_receipt_already_bound_to_another_account_is_rejected():
    class RejectingStore(InMemoryEntitlementStore):
        async def grant_pro(self, user_id, expires_at, product_id, purchase_token):
            raise RuntimeError("purchase token already bound to another user")

    quota = InMemoryQuotaStore(POLICY)
    svc = PlaySubscriptionService(StubVerifier(PlaySubscription("active")), RejectingStore(quota))

    assert await svc.verify_purchase("user-2", PRODUCT_MONTHLY, TOKEN) == "already_claimed"
    assert not (await quota.get_status("user-2")).is_pro


# --- lazy renewal refresh -------------------------------------------------------------------------


async def test_refresh_does_not_call_google_while_the_paid_period_is_running():
    svc, _, entitlements, stub = service(PlaySubscription("active"))
    await entitlements.grant_pro("user-1", NOW + timedelta(days=10), PRODUCT_MONTHLY, TOKEN)

    await svc.refresh("user-1")

    assert stub.calls == []


async def test_refresh_re_verifies_once_pro_has_lapsed():
    svc, quota, entitlements, stub = service(PlaySubscription("active", expires_at=NOW + timedelta(days=30)))
    await entitlements.grant_pro("user-1", NOW - timedelta(days=1), PRODUCT_MONTHLY, TOKEN)
    await entitlements.revoke_pro("user-1")

    await svc.refresh("user-1")

    assert stub.calls == [(PRODUCT_MONTHLY, TOKEN)]
    assert (await quota.get_status("user-1")).is_pro, "a renewed subscription restores PRO"


async def test_refresh_is_a_no_op_for_users_who_never_bought():
    svc, _, _, stub = service(PlaySubscription("active"))

    await svc.refresh("user-never-paid")

    assert stub.calls == []


async def test_refresh_never_raises_when_the_store_is_down():
    class BrokenStore:
        async def grant_pro(self, *args, **kwargs): ...
        async def revoke_pro(self, user_id): ...
        async def play_purchase(self, user_id):
            raise RuntimeError("supabase down")

    svc = PlaySubscriptionService(StubVerifier(PlaySubscription("active")), BrokenStore())

    await svc.refresh("user-1")  # must not raise: /v1/me has to keep working


# --- service-account key --------------------------------------------------------------------------


def test_a_pem_stored_with_escaped_newlines_is_restored():
    raw = '{"client_email": "a@b.iam.gserviceaccount.com", "private_key": "-----BEGIN-----\\\\nabc\\\\n-----END-----"}'
    key = ServiceAccountKey.from_json(raw)

    assert "\\n" not in key.private_key
    assert key.private_key.count("\n") == 2


def test_an_incomplete_service_account_key_is_rejected():
    with pytest.raises(ServiceAccountError):
        ServiceAccountKey.from_json('{"client_email": "a@b.com"}')

    with pytest.raises(ServiceAccountError):
        ServiceAccountKey.from_json("not json")


async def test_the_access_token_is_reused_until_it_expires():
    calls = 0

    def handler(request: httpx.Request) -> httpx.Response:
        nonlocal calls
        calls += 1
        return httpx.Response(200, json={"access_token": "t1", "expires_in": 3600})

    http = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    source = ServiceAccountTokenSource(http, _test_key())

    assert await source.token() == "t1"
    assert await source.token() == "t1"
    assert calls == 1


async def test_a_rejected_assertion_raises():
    http = httpx.AsyncClient(transport=httpx.MockTransport(lambda request: httpx.Response(400, json={"error": "invalid_grant"})))
    source = ServiceAccountTokenSource(http, _test_key())

    with pytest.raises(ServiceAccountError):
        await source.token()


def _test_key() -> ServiceAccountKey:
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import rsa

    pem = (
        rsa.generate_private_key(public_exponent=65537, key_size=2048)
        .private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        )
        .decode()
    )
    return ServiceAccountKey(client_email="a@b.iam.gserviceaccount.com", private_key=pem)
