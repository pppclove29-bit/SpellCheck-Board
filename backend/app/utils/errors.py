from typing import Literal

ErrorCode = Literal["INVALID_REQUEST", "UNAUTHORIZED", "NOT_FOUND", "METHOD_NOT_ALLOWED", "INTERNAL"]


class HttpError(Exception):
    """Raised anywhere in request handling; rendered as {"error": {"code", "message"}} with `status`."""

    def __init__(self, status: int, code: ErrorCode, message: str) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
