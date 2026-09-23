"""evidence path 解析与回校验（防幻觉的核心）。

冻结语法：`.属性` + `[下标]`，例如
    queue.dead_letter_entries_for_voucher[0].failure_reason
    metrics.counters.total_requests
    incident.detected_snapshot.redis_stock

规则：
- 属性访问只能落在对象上；下标访问只能落在数组上；
- 路径不存在 / 语法非法 → 该条证据被丢弃并计入 dropped；
- 校验通过的证据，`observed` 一律取自 **Context 真实值**（模型给的值不被信任）。
"""

import re
from typing import Any, List, Union

from models.diagnosis import Evidence, EvidenceValidation, LLMEvidence

_ATTR_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_INDEX_RE = re.compile(r"\d+\]")


class PathSyntaxError(ValueError):
    """path 语法不符合冻结语法。"""


class PathNotFound(LookupError):
    """path 语法正确，但在 Context 中不存在。"""


def parse_path(path: str) -> List[Union[str, int]]:
    """把 path 解析为 token 序列；非法语法抛 PathSyntaxError。

    冻结语法：`属性(.属性)*([下标])*`，例如 a.b[0].c
    """
    if not isinstance(path, str) or not path.strip():
        raise PathSyntaxError("empty path")
    text = path.strip()

    first = _ATTR_RE.match(text)
    if first is None:
        raise PathSyntaxError(f"path must start with an attribute: {path!r}")

    tokens: List[Union[str, int]] = [first.group(0)]
    pos = first.end()

    while pos < len(text):
        char = text[pos]
        if char == ".":
            attr = _ATTR_RE.match(text, pos + 1)
            if attr is None:
                raise PathSyntaxError(f"expected attribute after '.': {path!r}")
            tokens.append(attr.group(0))
            pos = attr.end()
        elif char == "[":
            index = _INDEX_RE.match(text, pos + 1)
            if index is None:
                raise PathSyntaxError(f"invalid index at {pos}: {path!r}")
            tokens.append(int(index.group(0)[:-1]))
            pos = index.end()
        else:
            raise PathSyntaxError(f"unexpected character {char!r} at {pos}: {path!r}")

    return tokens


def resolve_path(context: Any, path: str) -> Any:
    """按 token 序列回溯 Context；失败抛 PathSyntaxError / PathNotFound。"""
    current = context
    for token in parse_path(path):
        if isinstance(token, int):
            if not isinstance(current, list):
                raise PathNotFound(f"index access on non-array at {token}: {path!r}")
            if token >= len(current):
                raise PathNotFound(f"index {token} out of range: {path!r}")
            current = current[token]
        else:
            if not isinstance(current, dict):
                raise PathNotFound(f"attribute access on non-object at {token}: {path!r}")
            if token not in current:
                raise PathNotFound(f"missing attribute {token!r}: {path!r}")
            current = current[token]
    return current


def validate_evidence(
    context_json: dict,
    items: List[LLMEvidence],
    max_items: int,
) -> tuple[List[Evidence], EvidenceValidation]:
    """回校验模型给出的证据，返回（可用证据, 统计）。"""
    accepted: List[Evidence] = []
    seen_paths: set[str] = set()
    submitted = len(items)

    for item in items:
        if len(accepted) >= max_items:
            break
        path = item.path
        if path in seen_paths:
            # 重复路径不算新证据；保留第一条，其余计为 dropped
            continue
        try:
            observed = resolve_path(context_json, path)
        except (PathSyntaxError, PathNotFound):
            continue
        seen_paths.add(path)
        accepted.append(Evidence(path=path, observed=observed, note=item.note))

    return accepted, EvidenceValidation(
        submitted=submitted,
        accepted=len(accepted),
        dropped=submitted - len(accepted),
    )
