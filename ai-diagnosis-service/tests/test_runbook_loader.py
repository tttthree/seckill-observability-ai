"""Runbook KB 加载测试：逐条校验、异常一律降级 no-RAG、绝不阻塞服务。"""

import pytest
import yaml

from config import Settings
from models.runbook import Runbook
from services.runbook_loader import load_runbook_store, resolve_runbooks_dir

VALID = {
    "id": "rb-test-one",
    "title": "测试知识条目",
    "version": "1",
    "updated_at": "2026-09-24",
    "incident_types": ["INVENTORY_MISMATCH"],
    "match_signals": ["snapshot:deviation_positive"],
    "keywords": ["库存"],
    "summary": "用于测试的知识条目",
    "checks": ["检查库存"],
    "do_not": ["禁止自动覆盖库存"],
    "references": ["ARCHITECTURE.md"],
}


def write_runbook(directory, name: str, payload: dict) -> None:
    (directory / name).write_text(yaml.safe_dump(payload, allow_unicode=True), encoding="utf-8")


def make_settings(directory, enabled: bool = True) -> Settings:
    return Settings(rag_enabled=enabled, runbooks_dir=str(directory))


def test_loads_all_valid_entries(runbook_tmp_dir):
    write_runbook(runbook_tmp_dir, "b.yaml", {**VALID, "id": "rb-b"})
    write_runbook(runbook_tmp_dir, "a.yaml", {**VALID, "id": "rb-a"})

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.enabled is True
    assert store.ready is True
    assert store.count == 2
    assert store.invalid_count == 0
    assert store.reason == "ok"
    assert [item.id for item in store.runbooks] == ["rb-a", "rb-b"]  # 稳定顺序


def test_missing_directory_degrades_without_raising(runbook_tmp_dir):
    store = load_runbook_store(make_settings(runbook_tmp_dir / "not-exists"))

    assert store.enabled is True
    assert store.ready is False
    assert store.count == 0
    assert store.reason == "directory_missing"


def test_path_that_is_not_a_directory_degrades(runbook_tmp_dir):
    file_path = runbook_tmp_dir / "runbooks.yaml"
    file_path.write_text("id: x", encoding="utf-8")

    store = load_runbook_store(make_settings(file_path))

    assert store.ready is False
    assert store.reason == "directory_missing"


def test_empty_directory_degrades(runbook_tmp_dir):
    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.ready is False
    assert store.reason == "no_yaml_file"


def test_invalid_entry_is_skipped_but_others_load(runbook_tmp_dir):
    write_runbook(runbook_tmp_dir, "valid.yaml", VALID)
    (runbook_tmp_dir / "broken.yaml").write_text("id: [unclosed", encoding="utf-8")

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.ready is True
    assert store.count == 1
    assert store.invalid_count == 1


def test_all_invalid_degrades(runbook_tmp_dir):
    write_runbook(runbook_tmp_dir, "one.yaml", {**VALID, "id": "rb-one", "summary": ""})
    write_runbook(runbook_tmp_dir, "two.yaml", {**VALID, "id": "rb-two", "incident_types": ["NOT_A_TYPE"]})

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.ready is False
    assert store.count == 0
    assert store.invalid_count == 2
    assert store.reason == "no_valid_runbook"


def test_duplicate_id_marks_second_entry_invalid(runbook_tmp_dir):
    write_runbook(runbook_tmp_dir, "one.yaml", VALID)
    write_runbook(runbook_tmp_dir, "two.yaml", {**VALID, "title": "重复 id 的条目"})

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.count == 1
    assert store.invalid_count == 1


def test_unknown_signal_is_invalid(runbook_tmp_dir):
    write_runbook(
        runbook_tmp_dir,
        "bad-signal.yaml",
        {**VALID, "match_signals": ["consumer:pending_high", "no:such:signal"]},
    )

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.ready is False
    assert store.invalid_count == 1


def test_unknown_field_is_invalid(runbook_tmp_dir):
    write_runbook(runbook_tmp_dir, "extra.yaml", {**VALID, "severity_hint": "HIGH"})

    store = load_runbook_store(make_settings(runbook_tmp_dir))

    assert store.ready is False
    assert store.invalid_count == 1


def test_rag_disabled_never_reads_directory(runbook_tmp_dir):
    store = load_runbook_store(make_settings(runbook_tmp_dir / "does-not-exist", enabled=False))

    assert store.enabled is False
    assert store.ready is False
    assert store.count == 0
    assert store.reason == "disabled"


def test_unexpected_program_error_is_not_swallowed(runbook_tmp_dir, monkeypatch):
    """只有知识文件层面的预期异常才降级为 invalid；确定性程序错误必须 fail fast。"""
    write_runbook(runbook_tmp_dir, "valid.yaml", VALID)

    def boom(cls, value):
        raise RuntimeError("deterministic program error")

    monkeypatch.setattr(Runbook, "model_validate", classmethod(boom))

    with pytest.raises(RuntimeError):
        load_runbook_store(make_settings(runbook_tmp_dir))


def test_repository_runbooks_are_valid_and_ready():
    """仓库内 4 条 tracked Runbook 必须全部通过 schema（KB 内容回归护栏）。"""
    settings = Settings()

    store = load_runbook_store(settings)

    assert resolve_runbooks_dir(settings).is_dir()
    assert store.ready is True
    assert store.invalid_count == 0
    assert store.count == 4
    assert [item.id for item in store.runbooks] == [
        "rb-consumer-down",
        "rb-consumer-stalled",
        "rb-dead-letter-retry-exhausted",
        "rb-inventory-mismatch",
    ]
