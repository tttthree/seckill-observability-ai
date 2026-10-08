"""fixture 文本编码回归：中文必须是正常 UTF-8，不得残留二次编码 mojibake。

背景：fixture 曾因「UTF-8 字节被按 Latin-1 解码后再存成 UTF-8」而损坏
（例如 `Redis 与 MySQL` 变成 `Redis ä¸\x8e MySQL`）。本文件只校验测试数据的文本编码，
不涉及任何生产代码语义。
"""

import pytest

from conftest import FIXTURE_DIR, load_fixture

FIXTURES = [
    "incident_context_v3_0.json",
    "incident_context_missing_redis.json",
    "incident_context_missing_stock.json",
    "incident_context_dead_letter.json",
    "incident_context_consumer_unhealthy.json",
]

# 库存不一致类 fixture 共用的中文标题/描述
MISMATCH_FIXTURES = [
    "incident_context_v3_0.json",
    "incident_context_missing_redis.json",
    "incident_context_missing_stock.json",
]

EXPECTED_TITLE = "Redis 与 MySQL 库存持续不一致（voucherId=7001）"

# 典型 mojibake 片段（用户可见的 Latin-1 乱码特征）
MOJIBAKE_MARKERS = ("ä¸", "åº", "ï¼")


def _walk_strings(node, path="$"):
    if isinstance(node, dict):
        for key, value in node.items():
            yield from _walk_strings(value, f"{path}.{key}")
    elif isinstance(node, list):
        for index, value in enumerate(node):
            yield from _walk_strings(value, f"{path}[{index}]")
    elif isinstance(node, str):
        yield path, node


def _looks_double_encoded(value: str) -> bool:
    """字符串若能用 Latin-1 编码再按 UTF-8 解出汉字，即说明它是二次编码产物。"""
    try:
        recovered = value.encode("latin-1").decode("utf-8")
    except (UnicodeEncodeError, UnicodeDecodeError):
        return False
    return any("\u4e00" <= char <= "\u9fff" for char in recovered)


@pytest.mark.parametrize("name", MISMATCH_FIXTURES)
def test_fixture_title_is_correct_chinese(name):
    expected = (
        "Redis 与 MySQL 库存持续不一致（voucherId=7002）"
        if "missing_stock" in name
        else EXPECTED_TITLE
    )
    assert load_fixture(name)["incident"]["title"] == expected


@pytest.mark.parametrize("name", MISMATCH_FIXTURES)
def test_fixture_description_is_correct_chinese(name):
    description = load_fixture(name)["incident"]["description"]
    assert "连续两轮" in description
    assert "需人工介入" in description


def test_dead_letter_fixture_chinese_is_intact():
    payload = load_fixture("incident_context_dead_letter.json")
    incident = payload["incident"]
    assert incident["title"] == "订单消息重试超限进入死信队列（voucherId=7001）"
    assert "已隔离至死信队列" in incident["description"]
    assert "等待人工重放" in incident["description"]


def test_consumer_unhealthy_fixture_chinese_is_intact():
    payload = load_fixture("incident_context_consumer_unhealthy.json")
    assert payload["incident"]["title"] == "秒杀消费者线程不可用"
    assert "消费者心跳超时" in payload["incident"]["description"]
    assert "消费者心跳超时" in payload["consumer_health"]["reason"]


@pytest.mark.parametrize("name", FIXTURES)
def test_fixture_has_no_mojibake_markers_or_c1_controls(name):
    """原始文件文本中不得出现典型 mojibake 片段，也不得残留 C1 控制字符。"""
    raw = (FIXTURE_DIR / name).read_text(encoding="utf-8")

    for marker in MOJIBAKE_MARKERS:
        assert marker not in raw, f"{name} 仍含 mojibake 片段 {marker!r}"

    controls = sorted({ch for ch in raw if 0x80 <= ord(ch) <= 0x9F})
    assert controls == [], f"{name} 仍含 C1 控制字符 {[hex(ord(c)) for c in controls]}"


@pytest.mark.parametrize("name", FIXTURES)
def test_no_fixture_string_is_double_encoded(name):
    payload = load_fixture(name)
    offenders = [
        path for path, value in _walk_strings(payload) if _looks_double_encoded(value)
    ]
    assert offenders == [], f"{name} 存在二次编码字符串：{offenders}"


@pytest.mark.parametrize("name", FIXTURES)
def test_fixture_declares_v3_contract(name):
    assert load_fixture(name)["context_version"] == "v3.0"
