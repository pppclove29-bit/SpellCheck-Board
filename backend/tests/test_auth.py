import time

import jwt
import pytest

from app.services.auth import SupabaseJwtVerifier
from app.utils.errors import HttpError

pytestmark = pytest.mark.anyio

SECRET = "test-jwt-secret-with-at-least-32-characters"
SUPABASE_URL = "https://proj.supabase.co"
SUBJECT = "7c9e6679-7425-40de-944b-e07fc1f90ae7"
verifier = SupabaseJwtVerifier(f"{SUPABASE_URL}/", jwt_secret=SECRET)


def token(issuer: str = f"{SUPABASE_URL}/auth/v1", secret: str = SECRET, exp_in: int = 3600, anonymous: bool = False) -> str:
    now = int(time.time())
    claims = {"sub": SUBJECT, "iss": issuer, "aud": "authenticated", "role": "authenticated", "is_anonymous": anonymous, "iat": now, "exp": now + exp_in}
    return jwt.encode(claims, secret, algorithm="HS256")


async def test_valid_google_user_token_returns_subject() -> None:
    assert await verifier.authenticate({"authorization": f"Bearer {token()}"}) == SUBJECT


@pytest.mark.parametrize(
    "header",
    [
        None,
        f"Basic {token()}",
        f"Bearer {token(issuer='https://evil.example/auth/v1')}",
        f"Bearer {token(secret='another-secret-that-is-also-32-chars!!')}",
        f"Bearer {token(exp_in=-60)}",
        f"Bearer {token(anonymous=True)}",
    ],
    ids=["missing", "non-bearer", "wrong-issuer", "wrong-secret", "expired", "anonymous"],
)
async def test_rejects_bad_tokens(header: str | None) -> None:
    with pytest.raises(HttpError) as exc:
        await verifier.authenticate({"authorization": header} if header else {})
    assert (exc.value.status, exc.value.code) == (401, "UNAUTHORIZED")
