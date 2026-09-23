"""evidence path 解析与回校验（防幻觉的核心）。

冻结语法：`.属性` + `[下标]`，例如
    queue.dead_letter_entries_for_voucher[0].failure_reason
    metrics.counters.total_requests
    incident.detected_snapshot.redis_stock

规则：
- 属性访问只能落在对象上；下标访问只能落在数组上；
- path 不存在 / 语法非法 → 该条证据被丢弃并计入 dropped；
- 校验通过的证据，`observed` 一律取自 **Context 真实值**（模型给的值不被信任）；
- **counter_presence 语义由代码强制**：形如 `metrics.counters.<name>` 的路径，
  必须 `metrics.counter_presence.<name>` 严格为 true 才可通过；
  presence 为 false / 缺失 / 无法解析时一律 dropped（不得只依赖 system prompt）；
- 上限（max_items）只决定"是否进入最终 evidence"，超限的合法条目计入 over_limit，不计入 dropped。
"""

import re
from typing import Any, List, Sequence, Union

from models.diagnosis import Evidence, EvidenceValidation, LLMEvidence

_ATTR_RE = re.compile(r"[A-Za-z_][A-Za-z0-9_]*")
_INDEX_RE = re.compile(r"\d+\]")

# metrics.counters.<name> 的路径前缀
_COUNTERS_PREFIX = ("metrics", "counters")
_COUNTER_PRESENCE_TEMPLATE = "metrics.counter_presence.{name}"


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


def resolve_tokens(context: Any, tokens: Sequence[Union[str, int]], path: str) -> Any:
    """按 token 序列回溯 Context；失败抛 PathSyntaxError / PathNotFound。"""
    current = context
    for token in tokens:
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


def resolve_path(context: Any, path: str) -> Any:
    """按 path 回溯 Context；失败抛 PathSyntaxError / PathNotFound。"""
    return resolve_tokens(context, parse_path(path), path)


def is_counter_path(tokens: Sequence[Union[str, int]]) -> bool:
    """是否为 metrics.counters.<counter_name> 形态。"""
    return (
        len(tokens) >= 3
        and tokens[0] == _COUNTERS_PREFIX[0]
        and tokens[1] == _COUNTERS_PREFIX[1]
        and isinstance(tokens[2], str)
    )


def counter_presence_allows(context_json: Any, tokens: Sequence[Union[str, int]]) -> bool:
    """counter_presence 闸门：只有严格 true 才允许该 counter value 作为已验证证据。

    非 counter 路径不受此规则约束（返回 True）。
    """
    if not is_counter_path(tokens):
        return True
    name = str(tokens[2])
    try:
        presence = resolve_path(context_json, _COUNTER_PRESENCE_TEMPLATE.format(name=name))
    except (PathSyntaxError, PathNotFound):
        return False
    return presence is True


def validate_evidence(
    context_json: dict,
    items: List[LLMEvidence],
    max_items: int,
) -> tuple[List[Evidence], EvidenceValidation]:
    """回校验**全部** submitted 证据，返回（进入最终结果的证据, 统计）。

    每条证据都会依次经过：parse → resolve → duplicate → counter_presence。
    三种去向互斥且穷尽（冻结不变量 submitted == accepted + dropped + over_limit）：

    - dropped   ：被校验拒绝 —— path 语法非法 / path 不存在 / duplicate path /
                  metrics.counters.<name> 的 counter_presence 不严格为 true；
    - accepted  ：通过全部校验且未触及上限，进入最终 evidence；
    - over_limit：通过全部校验，但 accepted 已达 max_items，故不进入最终 evidence（**不是错误**）。

    注意：达到上限后**不会提前跳出**——超限部分仍会被完整校验，
    因此其中的 duplicate / presence 违规会被正确计入 dropped。
    """
    accepted: List[Evidence] = []
    seen_paths: set[str] = set()
    submitted = len(items)
    dropped = 0
    over_limit = 0

    for item in items:
        path = item.path

        # 1) 语法 + 2) 回溯 Context
        try:
            tokens = parse_path(path)
            observed = resolve_tokens(context_json, tokens, path)
        except (PathSyntaxError, PathNotFound):
            dropped += 1
            continue

        # 3) duplicate path：以"已通过 parse/resolve 的路径"为准
        #    （无论后续是否通过 counter_presence 或是否超限，都先占用该 path）
        if path in seen_paths:
            dropped += 1
            continue
        seen_paths.add(path)

        # 4) counter_presence 闸门（metrics.counters.<name> 必须严格 true）
        if not counter_presence_allows(context_json, tokens):
            dropped += 1
            continue

        # 5) 上限：有效但未进入最终 evidence
        if len(accepted) >= max_items:
            over_limit += 1
            continue

        accepted.append(Evidence(path=path, observed=observed, note=item.note))

    return accepted, EvidenceValidation(
        submitted=submitted,
        accepted=len(accepted),
        dropped=dropped,
        over_limit=over_limit,
    )
