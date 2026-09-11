"""Pydantic request/response models — the wire format of docs/api-contract.md."""

from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

FeedbackMode = Literal["spicy_wit", "police", "gentle"]
SuggestionType = Literal["spelling", "spacing", "grammar", "word_choice"]
AiStatus = Literal["used", "quota_exceeded", "unavailable", "paused", "skipped", "rate_limited"]


class GrammarCheckRequest(BaseModel):
    model_config = ConfigDict(extra="ignore")

    # Accepted for V4 compatibility, ignored: identity comes from the verified token.
    user_id: str | None = None
    text: str = Field(min_length=1)
    mode: FeedbackMode = "spicy_wit"


class Suggestion(BaseModel):
    offset: int  # UTF-16 code units
    length: int  # UTF-16 code units
    original_word: str
    suggested_word: str
    type: SuggestionType
    reason: str
    source: Literal["rule", "ai"]


class QuotaStatus(BaseModel):
    is_pro: bool
    limit: int
    used: int
    bonus: int
    remaining: int


class GrammarCheckResponse(BaseModel):
    original_text: str
    has_error: bool
    corrected_text: str
    wit_feedback: str | None
    suggestions: list[Suggestion]
    engine: Literal["rule", "hybrid"]
    ai_status: AiStatus
    # Shown to the user when AI is paused by the monthly budget guard; null otherwise.
    ai_notice: str | None = None
    quota: QuotaStatus


class MeResponse(BaseModel):
    user_id: str
    is_pro: bool
    quota: QuotaStatus
    ai_paused: bool = False


class HealthResponse(BaseModel):
    status: Literal["ok"] = "ok"
    ai: bool
