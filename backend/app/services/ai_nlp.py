"""2차 AI 문맥 교정·훈수 Engine interface. Input must already be PII-masked."""

from dataclasses import dataclass
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
    """Structured-output schema the LLM must fill.

    Deliberately omits original_text/corrected_text: the server already has the original and computes the
    corrected text from the verified suggestions, so asking the model to echo them only doubles output cost.
    """

    has_error: bool
    wit_feedback: str | None
    suggestions: list[AiSuggestion]


@dataclass(frozen=True)
class AiResult:
    analysis: AiAnalysis
    # Token usage for the monthly budget guard.
    prompt_tokens: int = 0
    completion_tokens: int = 0


class AiUnavailableError(Exception):
    """AI engine unreachable, timed out, refused, or returned unusable output. Callers degrade to rule-only results.

    Carries token usage when the model did run (refusal, truncation), so the spend still counts.
    """

    def __init__(self, message: str, *, prompt_tokens: int = 0, completion_tokens: int = 0) -> None:
        super().__init__(message)
        self.prompt_tokens = prompt_tokens
        self.completion_tokens = completion_tokens


class AiNlpService(Protocol):
    async def analyze(self, masked_text: str, mode: FeedbackMode) -> AiResult: ...
