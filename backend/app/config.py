import os
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class QuotaPolicy:
    free_daily_limit: int = 5
    pro_daily_limit: int = 300  # fair-use ceiling for "unlimited" PRO
    ad_reward_amount: int = 3
    max_ad_rewards_per_day: int = 5


@dataclass(frozen=True)
class Settings:
    max_text_length: int = 1000
    ai_enabled: bool = False
    openai_model: str = "gpt-4o-mini"
    ai_timeout_s: float = 6.0
    supabase_url: str | None = None
    supabase_service_role_key: str | None = None
    # Only for legacy HS256 Supabase projects; otherwise tokens are verified against the project's JWKS.
    supabase_jwt_secret: str | None = None
    # Local dev without Supabase: trust X-Dev-User-Id and keep quota in memory.
    insecure_dev_auth: bool = False
    quota: QuotaPolicy = QuotaPolicy()

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        def integer(name: str, default: int) -> int:
            try:
                value = int(env.get(name, ""))
            except ValueError:
                return default
            return value if value >= 0 else default

        insecure_dev_auth = env.get("ALLOW_INSECURE_DEV_AUTH") == "true"
        if insecure_dev_auth and env.get("VERCEL_ENV") == "production":
            raise RuntimeError("ALLOW_INSECURE_DEV_AUTH must never be enabled in production")

        return cls(
            max_text_length=integer("MAX_TEXT_LENGTH", 1000),
            # AI needs a key (AsyncOpenAI() raises without one, which would take down every endpoint);
            # AI_ENABLED=false turns it off even when a key is present.
            ai_enabled=bool(env.get("OPENAI_API_KEY")) and env.get("AI_ENABLED") != "false",
            openai_model=env.get("OPENAI_MODEL") or "gpt-4o-mini",
            ai_timeout_s=integer("AI_TIMEOUT_MS", 6000) / 1000,
            supabase_url=env.get("SUPABASE_URL") or None,
            supabase_service_role_key=env.get("SUPABASE_SERVICE_ROLE_KEY") or None,
            supabase_jwt_secret=env.get("SUPABASE_JWT_SECRET") or None,
            insecure_dev_auth=insecure_dev_auth,
            quota=QuotaPolicy(
                free_daily_limit=integer("FREE_DAILY_AI_LIMIT", 5),
                pro_daily_limit=integer("PRO_DAILY_AI_LIMIT", 300),
                ad_reward_amount=integer("AD_REWARD_AMOUNT", 3),
                max_ad_rewards_per_day=integer("MAX_AD_REWARDS_PER_DAY", 5),
            ),
        )
