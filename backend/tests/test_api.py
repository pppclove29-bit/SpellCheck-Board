import base64

import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec
from fastapi.testclient import TestClient

from app.api.container import Container
from app.main import create_app
from app.services.admob_ssv import AdmobKey
from app.services.auth import AuthVerifier, InsecureDevAuth, SupabaseJwtVerifier
from app.services.grammar_service import GrammarService
from app.services.quota import InMemoryQuotaStore
from app.utils.rule_engine import RuleEngine
from tests.conftest import POLICY

PRIVATE_KEY = ec.generate_private_key(ec.SECP256R1())
PUBLIC_PEM = PRIVATE_KEY.public_key().public_bytes(serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo).decode()


async def fake_keys(force_refresh: bool = False) -> list[AdmobKey]:
    return [AdmobKey(1234, PUBLIC_PEM)]


def make_client(auth: AuthVerifier | None = None) -> tuple[TestClient, InMemoryQuotaStore]:
    quota = InMemoryQuotaStore(POLICY)
    container = Container(
        grammar_service=GrammarService(RuleEngine(), None, quota),
        quota_store=quota,
        auth=auth or InsecureDevAuth(),
        fetch_admob_keys=fake_keys,
        max_text_length=20,
    )
    return TestClient(create_app(container)), quota


def signed_ssv_url(query: str, key_id: int = 1234) -> str:
    signature = PRIVATE_KEY.sign(query.encode(), ec.ECDSA(hashes.SHA256()))
    return f"/v1/ads/admob-ssv?{query}&signature={base64.urlsafe_b64encode(signature).decode().rstrip('=')}&key_id={key_id}"


DEV = {"x-dev-user-id": "u1"}


def test_grammar_check_returns_v4_response_and_ignores_body_user_id() -> None:
    client, _ = make_client()
    res = client.post("/v1/grammar-check", json={"user_id": "usr_spoofed", "text": "오늘 진짜 어의가 없네", "mode": "police"}, headers=DEV)
    assert res.status_code == 200
    body = res.json()
    assert body["original_text"] == "오늘 진짜 어의가 없네"
    assert body["corrected_text"] == "오늘 진짜 어이가 없네"
    assert body["wit_feedback"] == "🚨 맞춤법 위반 적발! '어의' → '어이' 교정 전까지 통과 불가!"
    assert body["ai_status"] == "unavailable"
    assert body["suggestions"][0] == {
        "offset": 6,
        "length": 2,
        "original_word": "어의",
        "suggested_word": "어이",
        "type": "spelling",
        "reason": "'어이없다'가 올바른 표기입니다.",
        "source": "rule",
    }


def test_mode_defaults_to_spicy_wit() -> None:
    client, _ = make_client()
    res = client.post("/v1/grammar-check", json={"text": "오늘 몇일이야?"}, headers=DEV)
    assert res.json()["wit_feedback"] == "'몇일'은 달력에 없는 날이에요. 며칠을 찾아도 안 나올걸요? 📅"


@pytest.mark.parametrize(
    "payload",
    ["{nope", {"text": ""}, {}, {"text": "안녕", "mode": "turbo"}, {"text": "가" * 21}],
    ids=["invalid-json", "empty-text", "missing-text", "invalid-mode", "too-long"],
)
def test_invalid_requests_get_400(payload) -> None:
    client, _ = make_client()
    if isinstance(payload, str):
        res = client.post("/v1/grammar-check", content=payload, headers={**DEV, "content-type": "application/json"})
    else:
        res = client.post("/v1/grammar-check", json=payload, headers=DEV)
    assert res.status_code == 400
    assert res.json()["error"]["code"] == "INVALID_REQUEST"


def test_wrong_method_and_unknown_route() -> None:
    client, _ = make_client()
    assert client.get("/v1/grammar-check").json()["error"]["code"] == "METHOD_NOT_ALLOWED"
    res = client.get("/nope")
    assert (res.status_code, res.json()["error"]["code"]) == (404, "NOT_FOUND")


def test_supabase_auth_requires_token() -> None:
    client, _ = make_client(SupabaseJwtVerifier("https://p.supabase.co", jwt_secret="x" * 32))
    res = client.post("/v1/grammar-check", json={"text": "안녕"})
    assert (res.status_code, res.json()["error"]["code"]) == (401, "UNAUTHORIZED")


def test_me_returns_plan_and_quota() -> None:
    client, _ = make_client()
    assert client.get("/v1/me", headers={"x-dev-user-id": "u9"}).json() == {
        "user_id": "u9",
        "is_pro": False,
        "quota": {"is_pro": False, "limit": 5, "used": 0, "bonus": 0, "remaining": 5},
    }


SSV_QUERY = "ad_network=5450213213286189855&ad_unit=1234567890&reward_amount=1&reward_item=AI&timestamp=1757570000000&transaction_id=txn-1&user_id=u1"


def test_ssv_credits_a_signed_reward_once() -> None:
    client, _ = make_client()
    first = client.get(signed_ssv_url(SSV_QUERY))
    assert (first.status_code, first.json()) == (200, {"status": "credited"})
    assert client.get(signed_ssv_url(SSV_QUERY)).json() == {"status": "duplicate"}
    quota = client.get("/v1/me", headers=DEV).json()["quota"]
    assert (quota["bonus"], quota["remaining"]) == (3, 8)


def test_ssv_rejects_tampering_unknown_keys_and_unsigned_calls() -> None:
    client, _ = make_client()
    assert client.get(signed_ssv_url(SSV_QUERY).replace("user_id=u1", "user_id=u2")).status_code == 400
    assert client.get(signed_ssv_url(SSV_QUERY, key_id=9999)).status_code == 400
    assert client.get(f"/v1/ads/admob-ssv?{SSV_QUERY}").status_code == 400


def test_health() -> None:
    client, _ = make_client()
    assert client.get("/health").json() == {"status": "ok", "ai": False}
