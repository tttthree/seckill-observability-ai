"""RAG 降级与证据语义回归：检索失败不得影响诊断，evidence 仍只认 IncidentContext。"""

import logging

from config import Settings
from conftest import StubDeepSeekClient
from models.diagnosis import LLMEvidence
from models.runbook import RetrievedRunbook
from services.diagnosis_service import DiagnosisService
from services.evidence_validator import validate_evidence
from services.runbook_retriever import RetrievalResult

GOOD_LLM = {
    "diagnosis_status": "DIAGNOSED",
    "root_cause": "券维度 Redis 库存采集中断，构建时刻库存与检测证据不一致。",
    "evidence": [
        {"path": "incident.status", "note": "事件已恢复"},
        {"path": "redis.voucher_stock.value", "note": "构建时刻库存"},
    ],
    "recommended_actions": [{"action": "人工核对库存", "rationale": "系统不自动覆盖"}],
    "insufficient_reason": None,
}

RUNBOOK = RetrievedRunbook(
    id="rb-test",
    title="测试知识",
    score=2,
    summary="知识摘要",
    checks=["知识检查项"],
    do_not=["禁止动作"],
)


class StubRetriever:
    """可编程检索器桩：返回固定结果或抛异常。"""

    def __init__(self, result=None, error=None):
        self._result = result if result is not None else RetrievalResult((), 0)
        self._error = error
        self.calls = 0

    def retrieve(self, context):
        self.calls += 1
        if self._error is not None:
            raise self._error
        return self._result


def make_service(retriever=None, payload=None, **overrides):
    settings = Settings(**overrides)
    client = StubDeepSeekClient(payload=payload if payload is not None else GOOD_LLM)
    return DiagnosisService(settings, client=client, retriever=retriever), client, settings


def test_retriever_exception_degrades_to_no_rag(context, caplog):
    retriever = StubRetriever(error=RuntimeError("kb exploded"))
    service, client, _ = make_service(retriever=retriever)

    with caplog.at_level(logging.WARNING):
        result = service.diagnose(context)

    assert result.diagnosis_status == "DIAGNOSED"
    assert client.calls == 1, "RAG 失败不得触发第二次模型调用"
    assert "（本次未检索到相关知识条目）" in client.last_prompt
    assert "rb-test" not in client.last_prompt
    assert any("rag retrieval failed" in record.message for record in caplog.records)


def test_no_retriever_means_no_knowledge_section(context):
    service, client, _ = make_service(retriever=None)

    result = service.diagnose(context)

    assert result.diagnosis_status == "DIAGNOSED"
    assert "（本次未检索到相关知识条目）" in client.last_prompt


def test_rag_disabled_does_not_call_retriever(context):
    retriever = StubRetriever(result=RetrievalResult((RUNBOOK,), 1))
    service, client, _ = make_service(retriever=retriever, rag_enabled=False)

    service.diagnose(context)

    assert retriever.calls == 0
    assert "知识摘要" not in client.last_prompt


def test_selected_runbook_is_injected_into_knowledge_section(context):
    retriever = StubRetriever(result=RetrievalResult((RUNBOOK,), 1))
    service, client, _ = make_service(retriever=retriever)

    result = service.diagnose(context)

    assert retriever.calls == 1
    assert result.diagnosis_status == "DIAGNOSED"
    knowledge_start = client.last_prompt.index("<runbook_knowledge>")
    assert "知识摘要" in client.last_prompt[knowledge_start:]
    assert "（本次未检索到相关知识条目）" not in client.last_prompt


def test_runbook_named_paths_cannot_become_evidence(context):
    """模型即使被知识条目诱导，也无法让 runbook 内容成为已验证证据。"""
    payload = {
        "diagnosis_status": "DIAGNOSED",
        "root_cause": "看似有根因",
        "evidence": [
            {"path": "incident.status", "note": "真实字段"},
            {"path": "runbook.checks[0]", "note": "编造的知识路径"},
            {"path": "rb-test.summary", "note": "编造的知识路径"},
        ],
        "recommended_actions": [],
        "insufficient_reason": None,
    }
    retriever = StubRetriever(result=RetrievalResult((RUNBOOK,), 1))
    service, client, _ = make_service(retriever=retriever, payload=payload)

    result = service.diagnose(context)

    assert [item.path for item in result.evidence] == ["incident.status"]
    assert result.evidence_validation.submitted == 3
    assert result.evidence_validation.accepted == 1
    assert result.evidence_validation.dropped == 2
    assert result.evidence_validation.over_limit == 0
    assert result.evidence_validation.submitted == (
        result.evidence_validation.accepted
        + result.evidence_validation.dropped
        + result.evidence_validation.over_limit
    )
    assert client.calls == 1


def test_validator_still_resolves_only_against_context(context):
    """validator 层直接回归：知识条目文本无法被解析为 path。"""
    context_json = context.model_dump(mode="json")
    items = [
        LLMEvidence(path="incident.status", note="ok"),
        LLMEvidence(path="rb-test.summary", note="runbook"),
        LLMEvidence(path="runbook.checks[0]", note="runbook"),
    ]

    accepted, validation = validate_evidence(context_json, items, max_items=10)

    assert [item.path for item in accepted] == ["incident.status"]
    assert validation.accepted == 1
    assert validation.dropped == 2
