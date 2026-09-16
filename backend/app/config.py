import os
from collections.abc import Mapping
from dataclasses import dataclass


@dataclass(frozen=True)
class QuotaPolicy:
    free_daily_limit: int = 5
    pro_daily_limit: int = 300  # fair-use ceiling for "unlimited" PRO
    ad_reward_amount: int = 3
    max_ad_rewards_per_day: int = 5
    # AI call attempts per KST day, independent of the 훈수 quota (which only charges when errors are found):
    # without this, a user typing only correct sentences could call the AI for free without limit.
    free_daily_attempts: int = 60
    pro_daily_attempts: int = 1000


@dataclass(frozen=True)
class BudgetPolicy:
    """Monthly OpenAI spend guard (UTC month, matching OpenAI billing)."""

    warn_usd: float = 100.0  # webhook alert
    cap_usd: float = 200.0  # AI calls pause, rule-only responses
    # gpt-4o-mini list price in USD per 1M tokens — verify against current OpenAI pricing before launch.
    price_input_per_m: float = 0.15
    price_output_per_m: float = 0.60


@dataclass(frozen=True)
class Settings:
    max_text_length: int = 1000
    ai_enabled: bool = False
    openai_model: str = "gpt-4o-mini"
    ai_timeout_s: float = 6.0
    # Cost/abuse bounds: longer texts get rule-only checks; the output cap fits ≤5 suggestions + one wit line.
    ai_max_input_chars: int = 150
    ai_max_output_tokens: int = 400
    supabase_url: str | None = None
    supabase_service_role_key: str | None = None
    # Only for legacy HS256 Supabase projects; otherwise tokens are verified against the project's JWKS.
    supabase_jwt_secret: str | None = None
    # Local dev without Supabase: trust X-Dev-User-Id and keep quota in memory.
    insecure_dev_auth: bool = False
    quota: QuotaPolicy = QuotaPolicy()
    budget: BudgetPolicy = BudgetPolicy()
    # Slack/Discord-compatible incoming webhook for budget alerts.
    budget_alert_webhook_url: str | None = None
    # Google Play subscription verification. Without both, purchases can't be verified and PRO is never granted.
    play_package_name: str | None = None
    google_service_account_json: str | None = None

    @classmethod
    def from_env(cls, env: Mapping[str, str] = os.environ) -> "Settings":
        def integer(name: str, default: int) -> int:
            try:
                value = int(env.get(name, ""))
            except ValueError:
                return default
            return value if value >= 0 else default

        def number(name: str, default: float) -> float:
            try:
                value = float(env.get(name, ""))
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
            ai_max_input_chars=integer("AI_MAX_INPUT_CHARS", 150),
            ai_max_output_tokens=integer("AI_MAX_OUTPUT_TOKENS", 400),
            supabase_url=env.get("SUPABASE_URL") or None,
            supabase_service_role_key=env.get("SUPABASE_SERVICE_ROLE_KEY") or None,
            supabase_jwt_secret=env.get("SUPABASE_JWT_SECRET") or None,
            insecure_dev_auth=insecure_dev_auth,
            quota=QuotaPolicy(
                free_daily_limit=integer("FREE_DAILY_AI_LIMIT", 5),
                pro_daily_limit=integer("PRO_DAILY_AI_LIMIT", 300),
                ad_reward_amount=integer("AD_REWARD_AMOUNT", 3),
                max_ad_rewards_per_day=integer("MAX_AD_REWARDS_PER_DAY", 5),
                free_daily_attempts=integer("FREE_DAILY_AI_ATTEMPTS", 60),
                pro_daily_attempts=integer("PRO_DAILY_AI_ATTEMPTS", 1000),
            ),
            budget=BudgetPolicy(
                warn_usd=number("AI_MONTHLY_WARN_USD", 100.0),
                cap_usd=number("AI_MONTHLY_CAP_USD", 200.0),
                price_input_per_m=number("OPENAI_PRICE_INPUT_PER_M", 0.15),
                price_output_per_m=number("OPENAI_PRICE_OUTPUT_PER_M", 0.60),
            ),
            budget_alert_webhook_url=env.get("BUDGET_ALERT_WEBHOOK_URL") or None,
            play_package_name=env.get("PLAY_PACKAGE_NAME") or None,
            google_service_account_json=env.get("GOOGLE_SERVICE_ACCOUNT_JSON") or None,
        )
