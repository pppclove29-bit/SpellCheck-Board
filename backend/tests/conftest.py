import json
from pathlib import Path

import pytest

from app.config import QuotaPolicy

REPO_ROOT = Path(__file__).resolve().parents[2]
POLICY = QuotaPolicy(free_daily_limit=5, pro_daily_limit=300, ad_reward_amount=3, max_ad_rewards_per_day=5)


@pytest.fixture
def anyio_backend() -> str:
    return "asyncio"


def load_golden() -> dict:
    return json.loads((REPO_ROOT / "shared" / "rule-golden-cases.json").read_text(encoding="utf-8"))
