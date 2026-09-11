import httpx
import pytest

from app.services.account import SupabaseAccountDeleter

pytestmark = pytest.mark.anyio

USER = "7c9e6679-7425-40de-944b-e07fc1f90ae7"


def deleter(status: int, seen: list[httpx.Request]) -> SupabaseAccountDeleter:
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(status)

    return SupabaseAccountDeleter(httpx.AsyncClient(transport=httpx.MockTransport(handle)), "https://p.supabase.co/", "service-key")


async def test_calls_auth_admin_api_with_service_role() -> None:
    seen: list[httpx.Request] = []
    await deleter(200, seen).delete(USER)
    req = seen[0]
    assert (req.method, str(req.url)) == ("DELETE", f"https://p.supabase.co/auth/v1/admin/users/{USER}")
    assert req.headers["authorization"] == "Bearer service-key" and req.headers["apikey"] == "service-key"


async def test_missing_user_is_idempotent() -> None:
    await deleter(404, []).delete(USER)


async def test_server_errors_raise() -> None:
    with pytest.raises(RuntimeError):
        await deleter(500, []).delete(USER)
