"""Dependency wiring. Built lazily once per warm serverless instance (keeps caches and HTTP pools alive)."""

import logging
from dataclasses import dataclass

import httpx
from fastapi import Request
from openai import AsyncOpenAI

from app.config import Settings
from app.services.admob_ssv import CachedAdmobKeyFetcher, KeyFetcher
from app.services.auth import AuthVerifier, InsecureDevAuth, SupabaseJwtVerifier
from app.services.grammar_service import GrammarService
from app.services.openai_nlp import OpenAiNlpService
from app.services.quota import InMemoryQuotaStore, QuotaStore, SupabaseQuotaStore
from app.utils.rule_engine import RuleEngine

log = logging.getLogger("typeright")


@dataclass
class Container:
    grammar_service: GrammarService
    quota_store: QuotaStore
    auth: AuthVerifier
    fetch_admob_keys: KeyFetcher
    max_text_length: int


def build_container(settings: Settings) -> Container:
    http = httpx.AsyncClient(timeout=5.0)

    quota_store: QuotaStore
    if settings.supabase_url and settings.supabase_service_role_key:
        quota_store = SupabaseQuotaStore(http, settings.supabase_url, settings.supabase_service_role_key, settings.quota)
    elif settings.insecure_dev_auth:
        quota_store = InMemoryQuotaStore(settings.quota)
    else:
        raise RuntimeError("SUPABASE_URL and SUPABASE_SERVICE_ROLE_KEY are required (or ALLOW_INSECURE_DEV_AUTH=true for local dev)")

    auth: AuthVerifier
    if settings.insecure_dev_auth:
        auth = InsecureDevAuth()
    elif settings.supabase_url:
        auth = SupabaseJwtVerifier(settings.supabase_url, settings.supabase_jwt_secret)
    else:
        raise RuntimeError("SUPABASE_URL is required (or ALLOW_INSECURE_DEV_AUTH=true for local dev)")

    ai = OpenAiNlpService(AsyncOpenAI(), settings.openai_model, settings.ai_timeout_s) if settings.ai_enabled else None
    grammar_service = GrammarService(
        RuleEngine(),
        ai,
        quota_store,
        on_ai_error=lambda err: log.warning("AI check degraded to rule engine: %s", err),
    )
    return Container(grammar_service, quota_store, auth, CachedAdmobKeyFetcher(http), settings.max_text_length)


def get_container(request: Request) -> Container:
    state = request.app.state
    if state.container is None:
        state.container = build_container(Settings.from_env())
    return state.container
