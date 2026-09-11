import pytest

from app.config import Settings


@pytest.mark.parametrize(
    ("env", "enabled"),
    [
        ({}, False),
        ({"AI_ENABLED": "true"}, False),  # no key: must not enable (AsyncOpenAI() would raise on every request)
        ({"OPENAI_API_KEY": "sk-test"}, True),
        ({"OPENAI_API_KEY": "sk-test", "AI_ENABLED": "false"}, False),
    ],
)
def test_ai_enabled_requires_key(env: dict[str, str], enabled: bool) -> None:
    assert Settings.from_env(env).ai_enabled is enabled


def test_insecure_dev_auth_refused_in_production() -> None:
    with pytest.raises(RuntimeError):
        Settings.from_env({"ALLOW_INSECURE_DEV_AUTH": "true", "VERCEL_ENV": "production"})


def test_quota_defaults_and_overrides() -> None:
    assert Settings.from_env({}).quota.free_daily_limit == 5
    assert Settings.from_env({"FREE_DAILY_AI_LIMIT": "10", "AI_TIMEOUT_MS": "4000"}).quota.free_daily_limit == 10
    assert Settings.from_env({"AI_TIMEOUT_MS": "4000"}).ai_timeout_s == 4.0
