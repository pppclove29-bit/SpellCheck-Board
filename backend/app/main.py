"""TypeRight API. Local: uvicorn app.main:app --reload --port 8790 --env-file .env"""

import logging

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException

from app.api.container import Container
from app.api.routes import router
from app.utils.errors import HttpError

log = logging.getLogger("typeright")

_STATUS_CODES = {401: "UNAUTHORIZED", 404: "NOT_FOUND", 405: "METHOD_NOT_ALLOWED"}


def _error(status: int, code: str, message: str) -> JSONResponse:
    return JSONResponse({"error": {"code": code, "message": message}}, status_code=status, headers={"cache-control": "no-store"})


def create_app(container: Container | None = None) -> FastAPI:
    """`container=None` builds dependencies from the environment on the first request."""
    app = FastAPI(title="TypeRight API", version="1.0.0", docs_url=None, redoc_url=None)
    app.state.container = container

    @app.exception_handler(HttpError)
    async def http_error(_: Request, exc: HttpError) -> JSONResponse:
        return _error(exc.status, exc.code, exc.message)

    # Contract uses 400 INVALID_REQUEST (FastAPI's default is 422). User text is never echoed into logs.
    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, exc: RequestValidationError) -> JSONResponse:
        message = "; ".join(f"{'.'.join(str(p) for p in e['loc'][1:]) or 'body'}: {e['msg']}" for e in exc.errors())
        return _error(400, "INVALID_REQUEST", message)

    @app.exception_handler(StarletteHTTPException)
    async def starlette_error(_: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _STATUS_CODES.get(exc.status_code, "INVALID_REQUEST" if exc.status_code < 500 else "INTERNAL")
        return _error(exc.status_code, code, str(exc.detail))

    @app.exception_handler(Exception)
    async def unhandled(_: Request, exc: Exception) -> JSONResponse:
        log.error("unhandled error: %s", type(exc).__name__, exc_info=exc)
        return _error(500, "INTERNAL", "Internal server error")

    app.include_router(router)
    return app


app = create_app()
