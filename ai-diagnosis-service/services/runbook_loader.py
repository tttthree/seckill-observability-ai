"""Runbook 知识库加载（启动时一次；逐条校验；绝不因 KB 问题拖死服务）。

降级原则（V2-5 冻结）：
- 目录缺失 / 不可读 / 无 YAML 文件 / 全部条目非法 → `rag_ready=False` + WARN，服务照常启动并退化 no-RAG；
- 单条非法（语法/schema/重复 id/未知信号）→ 跳过该条，其余可用，计入 `invalid_count`；
- 只有 Settings 非法或确定性程序错误才允许抛出（fail fast）。
"""

import logging
from dataclasses import dataclass
from pathlib import Path

import yaml
from pydantic import ValidationError

from config import Settings
from models.runbook import Runbook

logger = logging.getLogger(__name__)

# 服务根目录（services/ 的上一级），用于解析相对的 runbooks_dir
_SERVICE_ROOT = Path(__file__).resolve().parent.parent


@dataclass(frozen=True)
class RunbookStore:
    """KB 加载结果；`reason` 只用于日志与 /healthz，不进入业务判断。"""

    enabled: bool
    ready: bool
    runbooks: tuple
    invalid_count: int
    reason: str

    @property
    def count(self) -> int:
        return len(self.runbooks)


def resolve_runbooks_dir(settings: Settings) -> Path:
    root = Path(settings.runbooks_dir)
    return root if root.is_absolute() else (_SERVICE_ROOT / root)


def load_runbook_store(settings: Settings) -> RunbookStore:
    """按配置加载 KB；除 Settings/程序错误外不抛异常。"""
    if not settings.rag_enabled:
        logger.info("runbook kb disabled by config (RAG_ENABLED=false)")
        return RunbookStore(enabled=False, ready=False, runbooks=(), invalid_count=0, reason="disabled")

    directory = resolve_runbooks_dir(settings)
    try:
        if not directory.is_dir():
            logger.warning("runbook kb directory missing, degrade to no-RAG dir=%s", directory)
            return RunbookStore(True, False, (), 0, "directory_missing")
        files = sorted(directory.glob("*.yaml")) + sorted(directory.glob("*.yml"))
    except OSError as exc:
        logger.warning("runbook kb directory unreadable, degrade to no-RAG dir=%s error=%s",
                       directory, type(exc).__name__)
        return RunbookStore(True, False, (), 0, "directory_unreadable")

    if not files:
        logger.warning("runbook kb has no yaml file, degrade to no-RAG dir=%s", directory)
        return RunbookStore(True, False, (), 0, "no_yaml_file")

    valid = []
    invalid = 0
    seen_ids = set()
    for path in files:
        try:
            raw = yaml.safe_load(path.read_text(encoding="utf-8"))
            runbook = Runbook.model_validate(raw)
        except (OSError, UnicodeError, yaml.YAMLError, ValidationError) as exc:
            # 只吞「知识文件本身的问题」：IO / 编码 / YAML 语法 / schema 校验；
            # 其它异常（确定性程序错误）继续向上抛，fail fast。
            invalid += 1
            logger.warning("runbook invalid file=%s error=%s", path.name, type(exc).__name__)
            continue
        if runbook.id in seen_ids:
            invalid += 1
            logger.warning("runbook duplicate id file=%s", path.name)
            continue
        seen_ids.add(runbook.id)
        valid.append(runbook)

    if not valid:
        logger.warning("runbook kb has no valid entry, degrade to no-RAG dir=%s invalid=%s",
                       directory, invalid)
        return RunbookStore(True, False, (), invalid, "no_valid_runbook")

    ordered = tuple(sorted(valid, key=lambda item: item.id))
    logger.info("runbook kb loaded count=%s invalid=%s dir=%s", len(ordered), invalid, directory)
    return RunbookStore(True, True, ordered, invalid, "ok")
