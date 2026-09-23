"""fixture 文本编码回归：中文必须是正常 UTF-8，不得残留二次编码 mojibake。

背景：两个 fixture 曾因「UTF-8 字节被按 Latin-1 解码后再存成 UTF-8」而损坏
（例如 `Redis 与 MySQL` 变成 `Redis ä¸\x8e MySQL`）。本文件只校验测试数据的文本编码，
不涉及任何生产代码语义（V2-3 契约保持冻结）。
"""

import pytest

from conftest import FIXTURE_DIR, load_fixture

FIXTURES = [
    "incident_context_v2_2_1.json",
    "incident_context_missing_redis.json",
]

EXPECTED_TITLE = "Redis 与 MySQL 库存持续不一致（voucherId=7001）"

EXPECTED_NOTES = [
    "built_at 与各段 observed_at 为 UTC Instant；incident 段时间字段原样取自数据库 "
    "LocalDateTime，历史存储不含时区信息，未附加任何 offset",
    "incident 段（含 detected_snapshot）是检测时刻证据；其余各段是构建时刻读到的当前状态，两者不可混用",
    "detected_snapshot 仅保留最近一次检测证据（scope=LATEST_DETECTION），更早 occurrence 的证据已被聚合覆盖",
    "metrics.counters 为 Redis 计数器原始值；counter_presence=false 表示该计数器 key 不存在，"
    "按项目既有约定以 0 参与计算，可能代表无事件或已过期(TTL 7200s)",
    "detected_snapshot 与 recent_orders 均按白名单投影，不含 user_id 等用户标识",
    "logs 在当前版本不可用（无结构化日志源），列为 not_implemented_sources，不影响 complete",
]

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


@pytest.mark.parametrize("name", FIXTURES)
def test_fixture_title_is_correct_chinese(name):
    assert load_fixture(name)["incident"]["title"] == EXPECTED_TITLE


@pytest.mark.parametrize("name", FIXTURES)
def test_fixture_description_is_correct_chinese(name):
    description = load_fixture(name)["incident"]["description"]
    assert "连续两轮对账确认偏差" in description
    assert "需人工介入" in description


@pytest.mark.parametrize("name", FIXTURES)
def test_fixture_context_quality_notes_are_correct_chinese(name):
    notes = load_fixture(name)["context_quality"]["notes"]
    assert notes == EXPECTED_NOTES


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
