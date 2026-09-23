"""诊断输出契约。

分两层，这是 V2-3 的关键边界：

1. `LLMDiagnosis`：**LLM 只允许产出语义字段**
   （diagnosis_status / root_cause / evidence(path,note) / recommended_actions(action,rationale)
   / insufficient_reason）。它拿不到 `observed`、`requires_human`、`model`、时间戳等元数据。

2. `DiagnosisResult`：对外返回的完整结果，其余字段全部由 Python 服务生成或回填。
"""

from datetime import datetime
from enum import Enum
from typing import Any, List, Literal, Optional

from pydantic import BaseModel, ConfigDict, Field


class _StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DiagnosisStatus(str, Enum):
    DIAGNOSED = "DIAGNOSED"
    INSUFFICIENT_EVIDENCE = "INSUFFICIENT_EVIDENCE"
    UNAVAILABLE = "UNAVAILABLE"


class ErrorCode(str, Enum):
    UNSUPPORTED_CONTEXT_VERSION = "UNSUPPORTED_CONTEXT_VERSION"
    CONTEXT_TOO_LARGE = "CONTEXT_TOO_LARGE"
    PROMPT_TOO_LARGE = "PROMPT_TOO_LARGE"
    MODEL_NOT_CONFIGURED = "MODEL_NOT_CONFIGURED"
    MODEL_TIMEOUT = "MODEL_TIMEOUT"
    MODEL_UNREACHABLE = "MODEL_UNREACHABLE"
    MODEL_RATE_LIMITED = "MODEL_RATE_LIMITED"
    MODEL_AUTH_ERROR = "MODEL_AUTH_ERROR"
    MODEL_HTTP_ERROR = "MODEL_HTTP_ERROR"
    MODEL_OUTPUT_INVALID = "MODEL_OUTPUT_INVALID"
    INTERNAL = "INTERNAL"


# ==================== LLM 只允许产出的部分 ====================


class LLMEvidence(BaseModel):
    """模型给出的证据引用：只允许 path + note；observed 由服务回填真实值。

    对模型输出采用宽容策略（extra="ignore"），多余字段直接丢弃；
    服务随后用 evidence_validator 做严格回校验。输入契约的 extra="forbid" 不适用于这里。
    """

    model_config = ConfigDict(extra="ignore")

    path: str
    note: Optional[str] = None


class LLMAction(BaseModel):
    """模型给出的建议：只允许 action + rationale；requires_human 由服务固定为 true。"""

    model_config = ConfigDict(extra="ignore")

    action: str
    rationale: str


class LLMDiagnosis(BaseModel):
    """模型输出。

    这里刻意**不使用** extra="forbid" 之外的强约束（如 required-nullable），
    因为生产者是非确定性模型：字段缺失/多余字段用默认值与丢弃处理，
    随后由服务做语义校验（见 diagnosis_service）。这与输入契约的严格性要求并不冲突
    ——输入契约必须严，模型输出需要宽容 + 后验校验。
    """

    model_config = ConfigDict(extra="ignore")

    diagnosis_status: Literal["DIAGNOSED", "INSUFFICIENT_EVIDENCE"]
    root_cause: Optional[str] = None
    evidence: List[LLMEvidence] = Field(default_factory=list)
    recommended_actions: List[LLMAction] = Field(default_factory=list)
    insufficient_reason: Optional[str] = None


# ==================== 服务生成的最终结果 ====================


class Evidence(_StrictModel):
    """已回校验的证据：observed 必须是 Context 中的真实值。"""

    path: str
    observed: Any
    note: Optional[str]


class RecommendedAction(_StrictModel):
    action: str
    rationale: str
    requires_human: bool = True


class EvidenceValidation(_StrictModel):
    submitted: int
    accepted: int
    dropped: int


class DiagnosisResult(_StrictModel):
    diagnosis_status: Literal["DIAGNOSED", "INSUFFICIENT_EVIDENCE", "UNAVAILABLE"]
    context_version: str
    # Incident 主证据缺失时可为 null（禁止用 0 作为哨兵值）
    incident_id: Optional[int]
    incident_type: Optional[str]
    root_cause: Optional[str]
    evidence: List[Evidence]
    recommended_actions: List[RecommendedAction]
    insufficient_reason: Optional[str]
    error_code: Optional[str]
    evidence_validation: EvidenceValidation
    model: Optional[str]
    prompt_version: str
    diagnosed_at: datetime
    elapsed_ms: int


class HealthResponse(_StrictModel):
    status: str
    model_configured: bool
    supported_context_versions: List[str]
    prompt_version: str
