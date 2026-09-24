"""V2-3 AI Diagnosis Service 入口。

只做三件事：接收 V2-2 IncidentContext JSON → 调用模型做**语义**诊断 → 返回结构化 DiagnosisResult。
不连 Redis/MySQL，不执行任何运维动作，不做 RAG。
"""

import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse

from config import get_settings
from models.context import DiagnosisRequest
from models.diagnosis import DiagnosisResult, ErrorCode, HealthResponse
from services.diagnosis_service import (
    ContextTooLarge,
    DiagnosisService,
    PromptTooLarge,
    UnsupportedContextVersion,
)
from services.prompt_builder import PROMPT_VERSION
from services.runbook_loader import load_runbook_store
from services.runbook_retriever import RunbookRetriever

settings = get_settings()

logging.basicConfig(
    level=getattr(logging, settings.log_level.upper(), logging.INFO),
    format="%(asctime)s %(levelname)s %(name)s %(message)s",
)
logger = logging.getLogger("ai-diagnosis-service")

# V2-5：启动时一次性加载 Runbook KB。
# KB 目录缺失/不可读/为空/全部非法一律 rag_ready=False 并退化 no-RAG，不阻塞服务启动。
runbook_store = load_runbook_store(settings)
runbook_retriever = RunbookRetriever(runbook_store)

diagnosis_service = DiagnosisService(settings, retriever=runbook_retriever)


@asynccontextmanager
async def lifespan(_: FastAPI):
    # 只报告"是否配置"，绝不输出任何凭据内容
    logger.info(
        "startup model=%s model_configured=%s supported_context_versions=%s prompt_version=%s",
        settings.deepseek_model,
        settings.model_configured,
        settings.supported_versions,
        PROMPT_VERSION,
    )
    logger.info(
        "startup rag_enabled=%s rag_ready=%s runbook_count=%s invalid_runbook_count=%s reason=%s",
        runbook_store.enabled,
        runbook_store.ready,
        runbook_store.count,
        runbook_store.invalid_count,
        runbook_store.reason,
    )
    yield


app = FastAPI(
    title="Seckill Incident AI Diagnosis Service",
    version=PROMPT_VERSION,
    description="Consumes the frozen V2-2 IncidentContext contract and returns a structured diagnosis.",
    lifespan=lifespan,
)


@app.get("/healthz", response_model=HealthResponse)
def healthz() -> HealthResponse:
    return HealthResponse(
        status="ok",
        model_configured=settings.model_configured,
        supported_context_versions=settings.supported_versions,
        prompt_version=PROMPT_VERSION,
        rag_enabled=runbook_store.enabled,
        rag_ready=runbook_store.ready,
        runbook_count=runbook_store.count,
        invalid_runbook_count=runbook_store.invalid_count,
    )


@app.post("/api/v1/diagnosis", response_model=DiagnosisResult)
def diagnose(request: DiagnosisRequest) -> DiagnosisResult:
    """给定一份已构建好的 IncidentContext，返回结构化诊断。

    模型侧失败一律返回 200 + diagnosis_status=UNAVAILABLE（见各降级分支）；
    只有调用方输入问题才返回 4xx。
    """
    return diagnosis_service.diagnose(request.incident_context)


@app.exception_handler(RequestValidationError)
async def validation_exception_handler(_: Request, exc: RequestValidationError):
    """输入不符合冻结契约：返回字段定位，但不回显输入内容。"""
    details = [
        {
            "loc": [str(part) for part in error.get("loc", [])],
            "type": error.get("type"),
            "msg": error.get("msg"),
        }
        for error in exc.errors()
    ]
    return JSONResponse(
        status_code=422,
        content={
            "error_code": "INVALID_CONTEXT",
            "message": "incident_context 不符合 V2-2 冻结契约",
            "errors": details,
        },
    )


@app.exception_handler(UnsupportedContextVersion)
async def unsupported_version_handler(_: Request, exc: UnsupportedContextVersion):
    return JSONResponse(
        status_code=422,
        content={
            "error_code": exc.code.value,
            "message": "context_version 不受支持：契约升级必须显式升版",
            "context_version": exc.version,
            "supported_context_versions": exc.supported,
        },
    )


@app.exception_handler(ContextTooLarge)
async def context_too_large_handler(_: Request, exc: ContextTooLarge):
    return JSONResponse(
        status_code=413,
        content={
            "error_code": exc.code.value,
            "message": "序列化后的 incident_context 超过长度保护上限",
            "context_chars": exc.size,
            "limit": exc.limit,
        },
    )


@app.exception_handler(PromptTooLarge)
async def prompt_too_large_handler(_: Request, exc: PromptTooLarge):
    return JSONResponse(
        status_code=413,
        content={
            "error_code": exc.code.value,
            "message": "构造出的 prompt 超过长度保护上限",
            "prompt_chars": exc.size,
            "limit": exc.limit,
        },
    )


@app.exception_handler(Exception)
async def internal_error_handler(_: Request, exc: Exception):
    # 原始异常只进日志
    logger.exception("unexpected internal error: %s", type(exc).__name__)
    return JSONResponse(
        status_code=500,
        content={
            "error_code": ErrorCode.INTERNAL.value,
            "message": "内部错误，请查看服务日志",
        },
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host=settings.host, port=settings.port)
