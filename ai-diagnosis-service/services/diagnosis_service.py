"""诊断编排：校验 → Prompt → 单次模型调用 → 解析 → 证据回校验 → 生成最终结果。

边界：
- 只读：不访问 Redis/MySQL，不修改任何状态；
- 模型侧失败一律降级为 `UNAVAILABLE`（HTTP 200），让 Java 调用方无需为 AI 可用性写异常分支；
- 元数据（observed / requires_human / model / 时间 / 统计）全部由本服务生成。
"""

import logging
import time
from datetime import datetime, timezone
from typing import Optional

from pydantic import ValidationError

from config import Settings
from models.context import IncidentContext
from models.diagnosis import (
    DiagnosisResult,
    DiagnosisStatus,
    ErrorCode,
    EvidenceValidation,
    LLMDiagnosis,
    RecommendedAction,
)
from services.deepseek_client import DeepSeekClient, ModelCallError
from services.evidence_validator import validate_evidence
from services.prompt_builder import PROMPT_VERSION, build_prompt

logger = logging.getLogger(__name__)

_DEFAULT_INSUFFICIENT_REASON = "模型判定给定证据不足以给出确定结论。"

# Incident 主证据缺失时的稳定文案（不调用模型）
INCIDENT_MISSING_REASON = "Incident 主证据不可用，无法进行事件级诊断"


class UnsupportedContextVersion(Exception):
    """context_version 不在支持列表内（契约升级必须显式升版）。"""

    def __init__(self, version: str, supported: list[str]) -> None:
        super().__init__(ErrorCode.UNSUPPORTED_CONTEXT_VERSION.value)
        self.code = ErrorCode.UNSUPPORTED_CONTEXT_VERSION
        self.version = version
        self.supported = supported


class ContextTooLarge(Exception):
    """序列化后的 Context 超过长度保护上限。"""

    def __init__(self, size: int, limit: int) -> None:
        super().__init__(ErrorCode.CONTEXT_TOO_LARGE.value)
        self.code = ErrorCode.CONTEXT_TOO_LARGE
        self.size = size
        self.limit = limit


class PromptTooLarge(Exception):
    """构造出的 Prompt 超过长度保护上限。"""

    def __init__(self, size: int, limit: int) -> None:
        super().__init__(ErrorCode.PROMPT_TOO_LARGE.value)
        self.code = ErrorCode.PROMPT_TOO_LARGE
        self.size = size
        self.limit = limit


def _clean_model_output(content: str) -> str:
    """防御性清洗：去掉 markdown 代码块与首尾噪声。"""
    text = (content or "").strip()
    if text.startswith("```"):
        text = text.replace("```json", "").replace("```", "").strip()
    start, end = text.find("{"), text.rfind("}")
    if start >= 0 and end > start:
        text = text[start : end + 1]
    return text


class DiagnosisService:
    """无状态诊断编排器（可注入 stub client 以便离线测试）。"""

    def __init__(self, settings: Settings, client: Optional[DeepSeekClient] = None) -> None:
        self._settings = settings
        self._client = client if client is not None else DeepSeekClient(settings)

    def diagnose(self, context: IncidentContext) -> DiagnosisResult:
        started = time.monotonic()
        incident = context.incident

        # 1) 契约版本闸门：不匹配直接拒绝，不调用模型
        if context.context_version not in self._settings.supported_versions:
            raise UnsupportedContextVersion(
                context.context_version, self._settings.supported_versions
            )

        # 2) Incident 主证据缺失：直接给出稳定的 INSUFFICIENT_EVIDENCE，不调用模型
        if incident is None:
            logger.info(
                "diagnosis skipped: incident evidence missing context_version=%s",
                context.context_version,
            )
            return self._insufficient_without_model(
                context.context_version, started, INCIDENT_MISSING_REASON
            )

        # 3) 轻量长度保护（严格请求体限流留 V2-4）
        context_size = len(context.model_dump_json())
        if context_size > self._settings.max_context_chars:
            raise ContextTooLarge(context_size, self._settings.max_context_chars)

        prompt = build_prompt(context)
        if len(prompt) > self._settings.max_prompt_chars:
            raise PromptTooLarge(len(prompt), self._settings.max_prompt_chars)

        base = {
            "context_version": context.context_version,
            "incident_id": incident.incident_id,
            "incident_type": incident.incident_type,
            "prompt_version": PROMPT_VERSION,
        }

        # 4) 单次模型调用
        try:
            raw = self._client.complete_json(prompt)
        except ModelCallError as exc:
            logger.warning(
                "diagnosis model call failed incident_id=%s code=%s detail=%s",
                base["incident_id"],
                exc.code.value,
                exc.detail,
            )
            return self._unavailable(base, started, exc.code)

        # 4) 结构化解析（失败不重试）
        try:
            parsed = LLMDiagnosis.model_validate_json(_clean_model_output(raw))
        except ValidationError as exc:
            # 安全结构化诊断：只记录每个 error 的 loc / type；
            # 绝不记录模型完整原文、input、IncidentContext 或任何凭据。
            logger.warning(
                "diagnosis model output invalid incident_id=%s error_count=%s errors=%s",
                base["incident_id"],
                len(exc.errors()),
                [
                    {"loc": [str(part) for part in error.get("loc", ())], "type": error.get("type")}
                    for error in exc.errors()
                ],
            )
            return self._unavailable(base, started, ErrorCode.MODEL_OUTPUT_INVALID)
        except Exception as exc:  # noqa: BLE001 - 模型输出不可控
            logger.warning(
                "diagnosis model output unparsable incident_id=%s error=%s",
                base["incident_id"],
                type(exc).__name__,
            )
            return self._unavailable(base, started, ErrorCode.MODEL_OUTPUT_INVALID)

        # 5) 证据回校验：observed 一律取 Context 真实值
        context_json = context.model_dump(mode="json")
        evidence, validation = validate_evidence(
            context_json, parsed.evidence, self._settings.max_evidence_items
        )

        status = parsed.diagnosis_status
        root_cause = (parsed.root_cause or "").strip() or None
        insufficient_reason = (parsed.insufficient_reason or "").strip() or None

        # 6) 语义校验：DIAGNOSED 必须有可回溯的证据与 root_cause
        if status == DiagnosisStatus.DIAGNOSED.value:
            if not evidence:
                status = DiagnosisStatus.INSUFFICIENT_EVIDENCE.value
                insufficient_reason = (
                    "模型给出 DIAGNOSED，但其 evidence 中没有任何可回溯到 IncidentContext 的 path。"
                )
            elif root_cause is None:
                status = DiagnosisStatus.INSUFFICIENT_EVIDENCE.value
                insufficient_reason = "模型给出 DIAGNOSED，但 root_cause 为空。"

        # 7) 动作：截断 + 强制 requires_human
        actions = [
            RecommendedAction(action=a.action, rationale=a.rationale, requires_human=True)
            for a in parsed.recommended_actions[: self._settings.max_actions]
        ]

        # 8) INSUFFICIENT_EVIDENCE 统一正规化：
        #    无论是模型主动返回还是由 DIAGNOSED 降级而来，都不得携带 root_cause / actions / error_code；
        #    已通过回校验的 evidence 可以保留。
        if status == DiagnosisStatus.INSUFFICIENT_EVIDENCE.value:
            root_cause = None
            actions = []
            if insufficient_reason is None:
                insufficient_reason = _DEFAULT_INSUFFICIENT_REASON

        elapsed_ms = int((time.monotonic() - started) * 1000)
        logger.info(
            "diagnosis done incident_id=%s status=%s accepted_evidence=%s dropped_evidence=%s elapsed_ms=%s",
            base["incident_id"],
            status,
            validation.accepted,
            validation.dropped,
            elapsed_ms,
        )

        return DiagnosisResult(
            diagnosis_status=status,
            context_version=context.context_version,
            incident_id=base["incident_id"],
            incident_type=base["incident_type"],
            root_cause=root_cause,
            evidence=evidence,
            recommended_actions=actions,
            insufficient_reason=insufficient_reason,
            error_code=None,
            evidence_validation=validation,
            model=self._settings.deepseek_model,
            prompt_version=PROMPT_VERSION,
            diagnosed_at=datetime.now(timezone.utc),
            elapsed_ms=elapsed_ms,
        )

    def _insufficient_without_model(
        self, context_version: str, started: float, reason: str
    ) -> DiagnosisResult:
        """主证据缺失时的稳定降级结果：不调用模型，incident_id / incident_type 均为 null。"""
        return DiagnosisResult(
            diagnosis_status=DiagnosisStatus.INSUFFICIENT_EVIDENCE.value,
            context_version=context_version,
            incident_id=None,
            incident_type=None,
            root_cause=None,
            evidence=[],
            recommended_actions=[],
            insufficient_reason=reason,
            error_code=None,
            evidence_validation=EvidenceValidation(submitted=0, accepted=0, dropped=0),
            model=self._settings.deepseek_model,
            prompt_version=PROMPT_VERSION,
            diagnosed_at=datetime.now(timezone.utc),
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )

    def _unavailable(
        self, base: dict, started: float, code: ErrorCode
    ) -> DiagnosisResult:
        return DiagnosisResult(
            diagnosis_status=DiagnosisStatus.UNAVAILABLE.value,
            context_version=base["context_version"],
            incident_id=base["incident_id"],
            incident_type=base["incident_type"],
            root_cause=None,
            evidence=[],
            recommended_actions=[],
            insufficient_reason=None,
            error_code=code.value,
            evidence_validation=EvidenceValidation(submitted=0, accepted=0, dropped=0),
            model=self._settings.deepseek_model,
            prompt_version=PROMPT_VERSION,
            diagnosed_at=datetime.now(timezone.utc),
            elapsed_ms=int((time.monotonic() - started) * 1000),
        )
