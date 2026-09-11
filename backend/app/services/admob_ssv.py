"""AdMob rewarded-ad Server-Side Verification (SSV).

The signed message is the raw query string up to (excluding) "&signature="; the signature is URL-safe
base64 DER ECDSA-SHA256 and key_id selects Google's public key.
"""

import base64
import time
from collections.abc import Awaitable, Callable
from dataclasses import dataclass
from urllib.parse import parse_qsl

import httpx
from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import ec

VERIFIER_KEYS_URL = "https://www.gstatic.com/admob/reward/verifier-keys.json"


@dataclass(frozen=True)
class AdmobKey:
    key_id: int
    pem: str


KeyFetcher = Callable[[bool], Awaitable[list[AdmobKey]]]


class CachedAdmobKeyFetcher:
    """Fetches Google's verifier keys, cached per warm instance."""

    def __init__(self, http: httpx.AsyncClient, ttl_s: float = 24 * 3600) -> None:
        self._http = http
        self._ttl_s = ttl_s
        self._keys: list[AdmobKey] = []
        self._expires_at = 0.0

    async def __call__(self, force_refresh: bool = False) -> list[AdmobKey]:
        if force_refresh or time.monotonic() >= self._expires_at:
            res = await self._http.get(VERIFIER_KEYS_URL)
            res.raise_for_status()
            self._keys = [AdmobKey(int(k["keyId"]), k["pem"]) for k in res.json()["keys"]]
            self._expires_at = time.monotonic() + self._ttl_s
        return self._keys


def _b64url_decode(value: str) -> bytes:
    return base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))


async def verify_admob_ssv(raw_query: str, fetch_keys: KeyFetcher) -> tuple[bool, dict[str, str]]:
    """`raw_query` must be the undecoded query string (without "?"). Returns (valid, params)."""
    params = dict(parse_qsl(raw_query, keep_blank_values=True))
    sig_index = raw_query.find("&signature=")
    signature, key_id = params.get("signature"), params.get("key_id")
    if sig_index < 0 or not signature or not key_id:
        return False, params

    key = next((k for k in await fetch_keys(False) if str(k.key_id) == key_id), None)
    if key is None:  # Google rotates keys; refetch once before rejecting.
        key = next((k for k in await fetch_keys(True) if str(k.key_id) == key_id), None)
    if key is None:
        return False, params

    try:
        public_key = serialization.load_pem_public_key(key.pem.encode())
        if not isinstance(public_key, ec.EllipticCurvePublicKey):
            return False, params
        public_key.verify(_b64url_decode(signature), raw_query[:sig_index].encode(), ec.ECDSA(hashes.SHA256()))
        return True, params
    except (InvalidSignature, ValueError):
        return False, params
