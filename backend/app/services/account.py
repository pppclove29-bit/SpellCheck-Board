"""계정 삭제 (Google Play 계정 삭제 정책 대응).

Deleting the Supabase auth user cascades to every table (ON DELETE CASCADE). An active Play
subscription is not cancelled by this — the client tells the user to cancel it in Play Store.
"""

from typing import Protocol

import httpx

from app.services.quota import InMemoryQuotaStore


class AccountDeleter(Protocol):
    async def delete(self, user_id: str) -> None: ...


class SupabaseAccountDeleter:
    """Uses the Auth admin API; requires the service-role key."""

    def __init__(self, http: httpx.AsyncClient, supabase_url: str, service_role_key: str) -> None:
        self._http = http
        self._users_url = f"{supabase_url.rstrip('/')}/auth/v1/admin/users"
        self._headers = {"apikey": service_role_key, "Authorization": f"Bearer {service_role_key}"}

    async def delete(self, user_id: str) -> None:
        res = await self._http.delete(f"{self._users_url}/{user_id}", headers=self._headers)
        if res.status_code == 404:  # already gone: deletion is idempotent
            return
        if res.is_error:
            raise RuntimeError(f"Supabase user deletion failed: {res.status_code}")


class InMemoryAccountDeleter:
    """Local dev / tests: forgets the user's quota state."""

    def __init__(self, quota: InMemoryQuotaStore) -> None:
        self._quota = quota
        self.deleted: list[str] = []

    async def delete(self, user_id: str) -> None:
        self._quota.forget(user_id)
        self.deleted.append(user_id)
