import time
from collections import OrderedDict
from collections.abc import Callable
from typing import Generic, TypeVar

K = TypeVar("K")
V = TypeVar("V")


class TtlLruCache(Generic[K, V]):
    """Minimal in-memory LRU with TTL (per warm serverless instance)."""

    def __init__(self, max_entries: int, ttl_s: float, now: Callable[[], float] = time.monotonic) -> None:
        self._entries: OrderedDict[K, tuple[V, float]] = OrderedDict()
        self._max_entries = max_entries
        self._ttl_s = ttl_s
        self._now = now

    def get(self, key: K) -> V | None:
        entry = self._entries.pop(key, None)
        if entry is None or entry[1] <= self._now():
            return None
        self._entries[key] = entry
        return entry[0]

    def set(self, key: K, value: V) -> None:
        self._entries.pop(key, None)
        self._entries[key] = (value, self._now() + self._ttl_s)
        while len(self._entries) > self._max_entries:
            self._entries.popitem(last=False)

    def __len__(self) -> int:
        return len(self._entries)
