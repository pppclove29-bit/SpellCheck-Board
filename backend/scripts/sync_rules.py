"""Copies the single source of truth (../shared/korean-rules.json) into app/data/.

The copy is committed so Vercel bundles it with the function; tests/test_rules_sync.py fails if it drifts.
Run after editing shared/korean-rules.json:  python scripts/sync_rules.py
"""

import shutil
from pathlib import Path

BACKEND = Path(__file__).resolve().parent.parent
SOURCE = BACKEND.parent / "shared" / "korean-rules.json"
TARGET = BACKEND / "app" / "data" / "korean-rules.json"

if __name__ == "__main__":
    TARGET.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(SOURCE, TARGET)
    print(f"synced {SOURCE} -> {TARGET}")
