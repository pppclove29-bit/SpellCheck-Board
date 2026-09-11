"""2차 AI 문맥 교정·훈수 Engine interface. Input must already be PII-masked."""

from typing import Protocol

from pydantic import BaseModel

from app.schemas import FeedbackMode, SuggestionType


class AiSuggestion(BaseModel):
    """As returned by the LLM, in masked-text coordinates (UTF-16 offsets, untrusted)."""

    offset: int
    length: int
    original_word: str
    suggested_word: str
    type: SuggestionType
    reason: str


class AiAnalysis(BaseModel):
    """Structured-output schema the LLM must fill (V4 grammar-check JSON)."""

    original_text: str
    has_error: bool
    corrected_text: str
    wit_feedback: str | None
    suggestions: list[AiSuggestion]


class AiUnavailableError(Exception):
    """AI engine unreachable, timed out, refused, or returned unusable output. Callers degrade to rule-only results."""


class AiNlpService(Protocol):
    async def analyze(self, masked_text: str, mode: FeedbackMode) -> AiAnalysis: ...
