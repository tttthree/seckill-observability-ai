"""Claim-level grounding（V2-6）：claim citation 与最终 accepted evidence 的对应校验。

冻结边界（V2-6 amendment）：
- citation **不是第二套 evidence**：`root_cause_evidence_paths` / action `evidence_paths`
  只能引用**同一次模型输出里已经存在的 `evidence[].path`**；
- 本模块**不做** Context 解析，也不判断 path 合法性 —— `evidence_validator.py` 仍是
  path / observed / counter_presence 的唯一权威来源，本模块只在**最终 accepted evidence 的 path 集合**上做集合运算；
- 只允许对 `parsed.evidence` 做**稳定重排序**（citation 对应的条目优先），不新增、不删除任何 evidence item，
  因此 `submitted / accepted / dropped / over_limit` 四统计语义与 V2-3.3 完全一致（只有 accepted 的具体 path 集合可能变化）；
- citation 仅内部使用：不进入 `DiagnosisResult`、不进入 Java、不进入 `evidence_validation`、不落库。
"""

from dataclasses import dataclass
from typing import Iterable, List, Sequence, Set, Tuple

from models.diagnosis import LLMAction, LLMEvidence

# root_cause 至少需要 1 条最终 accepted 的 citation（不要求 ≥2）
MIN_ROOT_CITATIONS = 1


@dataclass(frozen=True)
class GroundingOutcome:
    """只包含计数，用于结构化日志（绝不记录 claim 正文）。"""

    root_cited: int
    root_accepted: int
    root_invalid: int
    dropped_actions: int
    downgraded: bool


def unique_paths(paths: Iterable[str]) -> List[str]:
    """按首次出现顺序去重（citation 计数以去重后的条数为准，保证确定性）。"""
    seen: Set[str] = set()
    ordered: List[str] = []
    for path in paths or []:
        if path not in seen:
            seen.add(path)
            ordered.append(path)
    return ordered


def prioritize_evidence(
    evidence: Sequence[LLMEvidence],
    root_paths: Iterable[str],
    action_paths: Iterable[str],
) -> List[LLMEvidence]:
    """稳定重排序：root citation 对应条目最前，其次 action citation，最后是未被引用的条目。

    - 纯重排：返回值与入参**元素完全相同**（仅顺序不同），不新增/删除/去重；
    - 每个分桶内部保持原相对顺序（stable），因此结果可确定性复现；
    - 同时被 root 与 action 引用的 path 归入 root 桶（root 优先级更高）。
    """
    root_set = set(root_paths or [])
    action_set = set(action_paths or [])
    root_bucket = [item for item in evidence if item.path in root_set]
    action_bucket = [
        item for item in evidence if item.path not in root_set and item.path in action_set
    ]
    rest_bucket = [
        item for item in evidence if item.path not in root_set and item.path not in action_set
    ]
    return root_bucket + action_bucket + rest_bucket


def collect_action_paths(actions: Sequence[LLMAction]) -> List[str]:
    paths: List[str] = []
    for action in actions or []:
        paths.extend(action.evidence_paths or [])
    return paths


def evaluate_root_grounding(root_paths: Iterable[str], accepted_paths: Set[str]) -> Tuple[int, int, int]:
    """返回 (root_cited, root_accepted, root_invalid)，均按去重后的 citation 计算。"""
    citations = unique_paths(root_paths)
    accepted = [path for path in citations if path in accepted_paths]
    return len(citations), len(accepted), len(citations) - len(accepted)


def filter_grounded_actions(
    actions: Sequence[LLMAction],
    accepted_paths: Set[str],
    max_actions: int,
) -> Tuple[List[LLMAction], int]:
    """保留至少 1 条 citation 落在最终 accepted evidence 中的 action。

    - 无 citation 或 citation 全部无效 → **只丢弃该 action**（不降级整份诊断）；
    - 先按 max_actions 截断（保持既有上限语义），再做 grounding 过滤。
    """
    kept: List[LLMAction] = []
    dropped = 0
    for action in list(actions or [])[:max_actions]:
        citations = unique_paths(action.evidence_paths)
        if not citations or not any(path in accepted_paths for path in citations):
            dropped += 1
            continue
        kept.append(action)
    return kept, dropped
