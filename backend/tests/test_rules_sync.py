from app.utils.rule_engine import RULES_PATH
from tests.conftest import REPO_ROOT


def test_bundled_rules_match_shared_source() -> None:
    """Fails when shared/korean-rules.json changed without running scripts/sync_rules.py."""
    shared = (REPO_ROOT / "shared" / "korean-rules.json").read_bytes()
    assert RULES_PATH.read_bytes() == shared, "run: python scripts/sync_rules.py"
