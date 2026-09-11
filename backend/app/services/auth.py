"""Resolves the caller's user id. The request body's user_id is never trusted."""

import asyncio
import re
from collections.abc import Mapping
from typing import Protocol

import jwt

from app.utils.errors import HttpError

_BEARER = re.compile(r"^Bearer\s+(\S+)$", re.IGNORECASE)


class AuthVerifier(Protocol):
    async def authenticate(self, headers: Mapping[str, str]) -> str: ...


class SupabaseJwtVerifier:
    """Verifies Supabase Auth access tokens (anonymous sign-in included) locally — no network hop per request."""

    def __init__(self, supabase_url: str, jwt_secret: str | None = None) -> None:
        base = supabase_url.rstrip("/")
        self._issuer = f"{base}/auth/v1"
        self._secret = jwt_secret
        # Asymmetric signing keys (current Supabase default) are fetched from the project's JWKS and cached.
        self._jwks = None if jwt_secret else jwt.PyJWKClient(f"{base}/auth/v1/.well-known/jwks.json", cache_keys=True, lifespan=3600)

    async def authenticate(self, headers: Mapping[str, str]) -> str:
        match = _BEARER.match(headers.get("authorization", ""))
        if not match:
            raise HttpError(401, "UNAUTHORIZED", "Missing bearer token")
        token = match.group(1)
        try:
            if self._secret:
                payload = jwt.decode(token, self._secret, algorithms=["HS256"], audience="authenticated", issuer=self._issuer)
            else:
                assert self._jwks is not None
                signing_key = await asyncio.to_thread(self._jwks.get_signing_key_from_jwt, token)
                payload = jwt.decode(token, signing_key.key, algorithms=["ES256", "RS256"], audience="authenticated", issuer=self._issuer)
        except jwt.PyJWTError as err:
            raise HttpError(401, "UNAUTHORIZED", "Invalid or expired token") from err
        subject = payload.get("sub")
        if not subject:
            raise HttpError(401, "UNAUTHORIZED", "Token has no subject")
        return subject


class InsecureDevAuth:
    """Local dev only (ALLOW_INSECURE_DEV_AUTH): trusts the X-Dev-User-Id header."""

    async def authenticate(self, headers: Mapping[str, str]) -> str:
        return headers.get("x-dev-user-id") or "dev-user"
