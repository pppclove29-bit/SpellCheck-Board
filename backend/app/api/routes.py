import logging

from fastapi import APIRouter, Depends, Request
from fastapi.responses import JSONResponse, Response

from app.api.container import Container, get_container
from app.schemas import GrammarCheckRequest, GrammarCheckResponse, HealthResponse, MeResponse
from app.services.admob_ssv import verify_admob_ssv
from app.utils.errors import HttpError

log = logging.getLogger("typeright")
router = APIRouter()


@router.post("/v1/grammar-check", response_model=GrammarCheckResponse)
async def grammar_check(body: GrammarCheckRequest, request: Request, c: Container = Depends(get_container)) -> GrammarCheckResponse:
    user_id = await c.auth.authenticate(request.headers)
    if len(body.text) > c.max_text_length:
        raise HttpError(400, "INVALID_REQUEST", f"text: at most {c.max_text_length} characters")
    return await c.grammar_service.check(user_id, body.text, body.mode)


@router.get("/v1/me", response_model=MeResponse)
async def me(request: Request, c: Container = Depends(get_container)) -> MeResponse:
    user_id = await c.auth.authenticate(request.headers)
    quota = await c.quota_store.get_status(user_id)
    return MeResponse(user_id=user_id, is_pro=quota.is_pro, quota=quota)


@router.delete("/v1/me", status_code=204)
async def delete_me(request: Request, c: Container = Depends(get_container)) -> Response:
    """Account deletion (Play policy). Cascades to quota, rewards, entitlements and shortcuts."""
    user_id = await c.auth.authenticate(request.headers)
    await c.account_deleter.delete(user_id)
    return Response(status_code=204)


@router.get("/v1/ads/admob-ssv")
async def admob_ssv(request: Request, c: Container = Depends(get_container)) -> JSONResponse:
    """Called by AdMob, not by clients. Any 200 stops AdMob's retries, so only bad signatures get 400."""
    valid, params = await verify_admob_ssv(request.url.query, c.fetch_admob_keys)
    if not valid:
        raise HttpError(400, "INVALID_REQUEST", "Invalid SSV signature")

    user_id, transaction_id = params.get("user_id"), params.get("transaction_id")
    if not user_id or not transaction_id:
        log.warning("SSV callback without user_id/transaction_id")
        return JSONResponse({"status": "ignored"})
    result = await c.quota_store.credit_ad_reward(user_id, transaction_id)
    if result != "credited":
        log.warning("SSV reward not credited: %s", result)
    return JSONResponse({"status": result})


@router.get("/health", response_model=HealthResponse)
async def health(c: Container = Depends(get_container)) -> HealthResponse:
    return HealthResponse(ai=c.grammar_service.ai_available)
