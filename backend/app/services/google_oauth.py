"""OAuth access tokens for Google APIs from a service-account key (JWT-bearer grant).

Only the Play Developer API needs this, and only one scope, so the flow is implemented directly instead of
pulling in google-auth: a signed assertion is exchanged for an access token, which is cached until it expires.
"""

import json
import time
from dataclasses import dataclass

import httpx
import jwt

TOKEN_URL = "https://oauth2.googleapis.com/token"
GRANT_TYPE = "urn:ietf:params:oauth:grant-type:jwt-bearer"
ANDROID_PUBLISHER_SCOPE = "https://www.googleapis.com/auth/androidpublisher"

# Assertions are short-lived; Google allows up to an hour.
_ASSERTION_TTL_S = 3600
# Renew a little early so an in-flight request never uses a token that expires mid-call.
_EXPIRY_SKEW_S = 60


class ServiceAccountError(RuntimeError):
    """The configured service-account key is unusable (missing fields, bad PEM, rejected by Google)."""


@dataclass(frozen=True)
class ServiceAccountKey:
    client_email: str
    private_key: str
    private_key_id: str | None = None

    @classmethod
    def from_json(cls, raw: str) -> "ServiceAccountKey":
        try:
            data = json.loads(raw)
        except json.JSONDecodeError as exc:
            raise ServiceAccountError("service account key is not valid JSON") from exc
        email, key = data.get("client_email"), data.get("private_key")
        if not email or not key:
            raise ServiceAccountError("service account key needs client_email and private_key")
        # Vercel env vars can't hold raw newlines, so the PEM is usually stored with literal \n.
        return cls(client_email=email, private_key=key.replace("\\n", "\n"), private_key_id=data.get("private_key_id"))


class ServiceAccountTokenSource:
    """Mints and caches one access token per warm instance."""

    def __init__(self, http: httpx.AsyncClient, key: ServiceAccountKey, scope: str = ANDROID_PUBLISHER_SCOPE) -> None:
        self._http = http
        self._key = key
        self._scope = scope
        self._token: str | None = None
        self._expires_at = 0.0

    async def token(self) -> str:
        if self._token and time.monotonic() < self._expires_at:
            return self._token
        res = await self._http.post(
            TOKEN_URL,
            data={"grant_type": GRANT_TYPE, "assertion": self._assertion()},
            headers={"content-type": "application/x-www-form-urlencoded"},
        )
        if res.is_error:
            # The body can echo the assertion; log only the status.
            raise ServiceAccountError(f"Google token exchange failed: {res.status_code}")
        body = res.json()
        token = body.get("access_token")
        if not token:
            raise ServiceAccountError("Google token exchange returned no access_token")
        self._token = token
        self._expires_at = time.monotonic() + max(0, int(body.get("expires_in", 3600)) - _EXPIRY_SKEW_S)
        return token

    def _assertion(self) -> str:
        now = int(time.time())
        claims = {
            "iss": self._key.client_email,
            "scope": self._scope,
            "aud": TOKEN_URL,
            "iat": now,
            "exp": now + _ASSERTION_TTL_S,
        }
        headers = {"kid": self._key.private_key_id} if self._key.private_key_id else None
        try:
            return jwt.encode(claims, self._key.private_key, algorithm="RS256", headers=headers)
        except Exception as exc:  # pragma: no cover - depends on the operator's key
            raise ServiceAccountError("could not sign the service-account assertion") from exc
