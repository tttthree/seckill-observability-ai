"""HTTP 层测试：200 语义、422/413 输入错误、/healthz、错误体不回显输入。"""

import copy
import json

import pytest
from fastapi.testclient import TestClient

import app as app_module
from config import Settings
from conftest import StubDeepSeekClient
from models.diagnosis import ErrorCode
from services.deepseek_client import ModelCallError
from services.diagnosis_service import DiagnosisService

GOOD_LLM = {
    "diagnosis_status": "DIAGNOSED",
    "root_cause": "构建时刻 Redis 与 MySQL 库存已一致，故障已恢复。",
    "evidence": [{"path": "incident.status", "note": "事件状态为 RESOLVED"}],
    "recommended_actions": [{"action": "人工复核对账记录", "rationale": "确认无残留偏差"}],
    "insufficient_reason": None,
}


def swap_service(service: DiagnosisService):
    """替换 app 模块级服务，返回恢复函数。"""
    original = app_module.diagnosis_service
    app_module.diagnosis_service = service
    return lambda: setattr(app_module, "diagnosis_service", original)


@pytest.fixture
def http(client_factory):
    return client_factory(Settings(), StubDeepSeekClient(payload=GOOD_LLM))


@pytest.fixture
def client_factory(context_payload):
    def factory(settings: Settings, client):
        restore = swap_service(DiagnosisService(settings, client=client))
        test_client = TestClient(app_module.app, raise_server_exceptions=False)
        return test_client, context_payload, restore

    return factory


def test_healthz_reports_configuration_without_secrets(http):
    test_client, _, _ = http
    response = test_client.get("/healthz")
    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "ok"
    assert body["prompt_version"] == "v2-5.1"
    assert body["supported_context_versions"] == ["v2-2.1"]
    assert isinstance(body["model_configured"], bool)
    assert "api_key" not in json.dumps(body)
    # V2-5：KB 状态可见（本仓库 runbooks/ 内 4 条有效知识，缺失/非法时也不阻塞服务启动）
    assert body["rag_enabled"] is True
    assert body["rag_ready"] is True
    assert body["runbook_count"] == 4
    assert body["invalid_runbook_count"] == 0


def test_diagnosis_happy_path(http):
    test_client, payload, restore = http
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": payload})
    finally:
        restore()

    assert response.status_code == 200
    body = response.json()
    assert body["diagnosis_status"] == "DIAGNOSED"
    assert body["context_version"] == "v2-2.1"
    assert body["evidence"][0]["path"] == "incident.status"
    assert body["evidence"][0]["observed"] == "RESOLVED"
    assert body["recommended_actions"][0]["requires_human"] is True
    assert body["error_code"] is None
    # V2-3.3：HTTP 契约必须显式给出 over_limit（与 dropped 分离）
    assert body["evidence_validation"] == {
        "submitted": 1,
        "accepted": 1,
        "dropped": 0,
        "over_limit": 0,
    }


def test_api_separates_over_limit_from_dropped(client_factory):
    """V2-3.3：12 条全合法证据 + 上限 10 → HTTP 响应 accepted=10 / dropped=0 / over_limit=2。"""
    valid_paths = [
        "incident.status",
        "incident.incident_id",
        "incident.severity",
        "incident.source",
        "incident.business_key",
        "incident.occurrence_count",
        "incident.detected_snapshot.redis_stock",
        "incident.detected_snapshot.db_stock",
        "incident.detected_snapshot.deviation",
        "redis.voucher_stock.value",
        "database.seckill_voucher.stock",
        "metrics.counters.total_requests",
    ]
    payload = dict(GOOD_LLM)
    payload["evidence"] = [{"path": p, "note": None} for p in valid_paths]

    test_client, request_payload, restore = client_factory(
        Settings(max_evidence_items=10), StubDeepSeekClient(payload=payload)
    )
    try:
        response = test_client.post(
            "/api/v1/diagnosis", json={"incident_context": request_payload}
        )
    finally:
        restore()

    assert response.status_code == 200
    body = response.json()
    assert body["diagnosis_status"] == "DIAGNOSED"
    assert body["evidence_validation"] == {
        "submitted": 12,
        "accepted": 10,
        "dropped": 0,
        "over_limit": 2,
    }
    assert len(body["evidence"]) == 10
    assert [e["path"] for e in body["evidence"]] == valid_paths[:10]


def test_invalid_contract_returns_422_without_echoing_input(http, ):
    test_client, payload, restore = http
    broken = copy.deepcopy(payload)
    del broken["incident"]["status"]
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": broken})
    finally:
        restore()

    assert response.status_code == 422
    body = response.json()
    assert body["error_code"] == "INVALID_CONTEXT"
    assert "input" not in body
    assert "voucher:7001" not in json.dumps(body), "错误体不得回显业务数据"


def test_extra_field_returns_422(http):
    test_client, payload, restore = http
    broken = copy.deepcopy(payload)
    broken["incident"]["future_field"] = 1
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": broken})
    finally:
        restore()

    assert response.status_code == 422
    assert response.json()["error_code"] == "INVALID_CONTEXT"


def test_unsupported_context_version_returns_422(http):
    test_client, payload, restore = http
    broken = copy.deepcopy(payload)
    broken["context_version"] = "v2-1.0"
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": broken})
    finally:
        restore()

    assert response.status_code == 422
    body = response.json()
    assert body["error_code"] == "UNSUPPORTED_CONTEXT_VERSION"
    assert body["supported_context_versions"] == ["v2-2.1"]


def test_context_too_large_returns_413(client_factory):
    test_client, payload, restore = client_factory(
        Settings(max_context_chars=50), StubDeepSeekClient(payload=GOOD_LLM)
    )
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": payload})
    finally:
        restore()

    assert response.status_code == 413
    assert response.json()["error_code"] == "CONTEXT_TOO_LARGE"


def test_model_unavailable_still_returns_200(client_factory):
    """模型侧失败不得变成 5xx：Java 调用方无需为 AI 可用性写异常分支。"""
    test_client, payload, restore = client_factory(
        Settings(), StubDeepSeekClient(error=ModelCallError(ErrorCode.MODEL_TIMEOUT, "timeout"))
    )
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": payload})
    finally:
        restore()

    assert response.status_code == 200
    body = response.json()
    assert body["diagnosis_status"] == "UNAVAILABLE"
    assert body["error_code"] == "MODEL_TIMEOUT"
    assert body["evidence"] == []


def test_missing_incident_returns_stable_insufficient_evidence(http):
    """incident=null：200 + INSUFFICIENT_EVIDENCE + incident_id=null，且不调用模型。"""
    test_client, payload, restore = http
    broken = copy.deepcopy(payload)
    broken["incident"] = None
    try:
        response = test_client.post("/api/v1/diagnosis", json={"incident_context": broken})
    finally:
        restore()

    assert response.status_code == 200
    body = response.json()
    assert body["diagnosis_status"] == "INSUFFICIENT_EVIDENCE"
    assert body["incident_id"] is None
    assert body["incident_type"] is None
    assert body["root_cause"] is None
    assert body["evidence"] == []
    assert body["recommended_actions"] == []
    assert body["error_code"] is None
    assert body["insufficient_reason"] == "Incident 主证据不可用，无法进行事件级诊断"
