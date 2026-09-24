# V2-3 / V2-5 AI Diagnosis Service

接收 **V2-2 冻结的 `IncidentContext` JSON**，调用 DeepSeek 输出**结构化诊断结果**；
V2-5 起额外注入**人工评审的 Runbook 通用知识**（RAG，确定性检索，见下文）。

边界（冻结）：

- Java 仍是主系统；本服务**只读**——不连 Redis/MySQL，不执行任何运维动作；
- 模型只产出**语义字段**（`diagnosis_status` / `root_cause` / `evidence(path,note)` /
  `recommended_actions(action,rationale)` / `insufficient_reason`），
  其余元数据（`observed`、`requires_human`、`evidence_validation`、`model`、时间、`error_code`）全部由本服务生成；
- 模型侧失败一律返回 **HTTP 200 + `diagnosis_status=UNAVAILABLE`**，让 Java 调用方无需为 AI 可用性写异常分支；
- 单次调用、**不做 retry/backoff**（retry / 限流 / 熔断在 Java 侧 V2-4 实现）；
- Runbook 只是**通用知识**，不是本次事故的事实；`evidence[].path` **只能**引用 IncidentContext。

## 运行

```powershell
# 环境（Python 3.12 x64）
py -3.12 -m venv .venv
.venv\Scripts\python -m pip install -r requirements.txt

# 启动（默认 127.0.0.1:8000）
.venv\Scripts\python -m uvicorn app:app --host 127.0.0.1 --port 8000
```

配置见 `.env.example`（复制为 `.env` 后填写；`.env` 不会进入版本库）。`DEEPSEEK_API_KEY` 与 Java 侧同名。

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/healthz` | 服务与配置状态（`model_configured` + V2-5 KB 状态；绝不返回凭据与知识内容） |
| POST | `/api/v1/diagnosis` | 入参 `{"incident_context": {...}}`，返回 `DiagnosisResult` |

请求示例：

```bash
curl -s -X POST http://127.0.0.1:8000/api/v1/diagnosis \
  -H "Content-Type: application/json" \
  -d "{\"incident_context\": $(cat tests/fixtures/incident_context_v2_2_1.json)}"
```

响应示例（节选）：

```json
{
  "diagnosis_status": "DIAGNOSED",
  "context_version": "v2-2.1",
  "incident_id": 9001,
  "incident_type": "INVENTORY_MISMATCH",
  "root_cause": "……",
  "evidence": [
    {"path": "redis.voucher_stock.value", "observed": 1, "note": "构建时刻 Redis 库存已为 1"},
    {"path": "incident.detected_snapshot.redis_stock", "observed": 0, "note": "最近一次检测证据显示为 0"}
  ],
  "recommended_actions": [
    {"action": "人工核对券 7001 的 Redis 与 MySQL 库存", "rationale": "系统不自动覆盖库存", "requires_human": true}
  ],
  "insufficient_reason": null,
  "error_code": null,
  "evidence_validation": {"submitted": 2, "accepted": 2, "dropped": 0, "over_limit": 0},
  "model": "deepseek-flash",
  "prompt_version": "v2-5.1",
  "diagnosed_at": "2026-01-15T08:00:12.345Z",
  "elapsed_ms": 4210
}
```

## 契约对齐（`context_version = v2-2.1`）

- 输入模型 `extra="forbid"`：**契约升级必须显式升版**，不做隐式前向兼容；
- 冻结契约中**所有 key 必存在**（Java 侧 `@JsonInclude(ALWAYS)`），因此字段一律 required，
  可空字段写成 required nullable —— 字段缺失会 422，不会被静默当成 null；
- 段为 `null` = 未计划或不可用；`present=false` 时 `value=null`（absent ≠ 0）；
- `counter_presence=false` 的计数器数值只是项目缺省约定，Prompt 明确禁止当作真实观测值；
- 时间语义：`built_at`/`observed_at` 为 UTC Instant；`incident.*_detected_at` 为无时区原值；
  `detected_snapshot`(scope=`LATEST_DETECTION`) 是"最近一次检测证据"，与构建时刻状态严格区分。

## 降级矩阵

| 场景 | HTTP | diagnosis_status | error_code |
|---|---|---|---|
| 输入不符合契约 | 422 | — | `INVALID_CONTEXT` |
| `context_version` 不支持 | 422 | — | `UNSUPPORTED_CONTEXT_VERSION`（不调用模型） |
| Context/Prompt 超过长度保护 | 413 | — | `CONTEXT_TOO_LARGE` / `PROMPT_TOO_LARGE` |
| 未配置 `DEEPSEEK_API_KEY` | 200 | `UNAVAILABLE` | `MODEL_NOT_CONFIGURED` |
| 超时 / 连接失败 / 429 / 5xx / 401 | 200 | `UNAVAILABLE` | `MODEL_TIMEOUT` / `MODEL_UNREACHABLE` / `MODEL_RATE_LIMITED` / `MODEL_HTTP_ERROR` / `MODEL_AUTH_ERROR` |
| 模型输出非 JSON | 200 | `UNAVAILABLE` | `MODEL_OUTPUT_INVALID`（不重试） |
| 模型判证据不足 | 200 | `INSUFFICIENT_EVIDENCE` | `null` |
| `DIAGNOSED` 但 evidence 全不可回溯 | 200 | `INSUFFICIENT_EVIDENCE` | `null` |
| **`incident` 主证据缺失（`incident=null`）** | 200 | `INSUFFICIENT_EVIDENCE` | `null`（**不调用模型**，`incident_id`/`incident_type` 为 `null`） |
| **Runbook KB 缺失 / 不可读 / 为空 / 全部非法** | 200 | 正常诊断（no-RAG） | `null`（`rag_ready=false`，**不阻塞服务启动**） |
| **请求期检索异常** | 200 | 正常诊断（no-RAG） | `null`（只记 WARN，不影响本次诊断） |
| 未预期内部错误 | 500 | — | `INTERNAL`（原始异常只进日志） |

`INSUFFICIENT_EVIDENCE` 统一正规化：无论模型主动返回还是由 `DIAGNOSED` 降级而来，最终响应一律
`root_cause=null`、`recommended_actions=[]`、`error_code=null`（已通过回校验的 `evidence` 保留）。

## 防幻觉机制

`evidence[].path` 冻结语法为 `.属性` + `[下标]`（如
`queue.dead_letter_entries_for_voucher[0].failure_reason`）。
服务会按该语法把每条 path 回溯到输入 Context：

- 能回溯 → 保留，并把 `observed` **覆盖为 Context 真实值**（模型给的值不被信任）；
- 不能回溯 → 丢弃并计入 `evidence_validation.dropped`；
- **`metrics.counters.<name>` 形态的路径由代码强制 `counter_presence` 语义**：
  必须 `metrics.counter_presence.<name>` **严格为 true** 才可通过；
  `presence=false` / 缺失 / 无法解析一律 dropped（不依赖 system prompt）；
- `DIAGNOSED` 但没有任何可回溯证据 → 强制降级为 `INSUFFICIENT_EVIDENCE`。

### `evidence_validation`：dropped 与 over_limit 的区别（V2-3.3）

模型提交的**每一条** `evidence` 都会走完整校验链（`parse → resolve → duplicate → counter_presence`），
**不会因为数量达到上限而提前停止校验**。三种去向互斥且穷尽，冻结全局不变量：

```
submitted == accepted + dropped + over_limit
```

| 字段 | 含义 |
|---|---|
| `submitted` | 模型提交的 evidence 总条数 |
| `accepted` | 通过全部校验、并进入最终 `evidence` 的条数 |
| `dropped` | **被校验拒绝**：path 语法非法 / path 不存在 / duplicate path / `counter_presence` 不严格为 true |
| `over_limit` | **条目本身完全合法**，但 `accepted` 已达 `MAX_EVIDENCE_ITEMS`，因此未进入最终 `evidence`（**不是错误**） |

因此：

- `dropped > 0` 表示模型编造或重复引用了证据，是**质量信号**；
- `over_limit > 0` 只表示有效证据多于输出上限（`MAX_EVIDENCE_ITEMS`，默认 10），是**截断信号**，不代表模型出错；
- 上限不改变判定语义：达到上限后提交的非法条目仍计入 `dropped`（不会因为"超限"而免检）；
  重复路径也以"已通过 parse/resolve 的路径"为准，超限区间内的重复同样计入 `dropped`。

## Runbook KB 与 RAG（V2-5）

在 IncidentContext 之外，服务还会注入**通用运维知识**（Runbook），帮助模型组织排查步骤；
知识**不是**本次事故的事实，`evidence` 仍然只能引用 IncidentContext（结构上由 validator 保证）。

### 知识格式与 schema

一条知识一个 YAML 文件，位于 `runbooks/`（启动时一次性加载，构建后只读内存，无请求期 IO）：

```yaml
id: rb-inventory-mismatch            # ^[a-z0-9][a-z0-9-]{2,63}$，全库唯一
title: 库存不一致（Redis vs MySQL）处置手册
version: "1"                         # 条目自身版本
updated_at: 2026-09-24
incident_types: [INVENTORY_MISMATCH] # 必须声明，取值 ∈ IncidentType
match_signals:                       # 可选；取自闭集信号词表（拼错该条即 invalid）
  - snapshot:deviation_positive
  - counter_present:reconcile_mismatch
keywords: [库存, 对账, deviation]     # 自由文本兜底匹配，≤20 个
summary: 一句话适用场景（≤200 字）     # ↓ 仅这三个字段进入 prompt
checks: [ ... ≤8 条，每条 ≤200 字 ... ]
do_not: [ ... ≤5 条，每条 ≤200 字 ... ]
references: [ARCHITECTURE.md#5-一致性边界]   # 仅人工溯源，不投喂模型
```

`extra="forbid"`：字段写错、类型非法、未知信号名、重复 id → 该条目 **invalid**（跳过 + WARN），其余条目照常可用。

### 检索语义（确定性，无 embedding / 无向量库 / 无分词依赖）

1. **`incident_type` 硬过滤**：只在该故障类型的条目内排序；
2. 打分 `signal_hit * 2 + keyword_hit * 1`（keyword 最多计 3 个）；keyword 为该条目声明的词在
   `incident.title` / `incident.description` / 快照 reason / `consumer_health.reason` 中的**子串命中**（小写比较）；
3. 排序 `score 降序 → id 升序`，取 **Top-2**；
4. **无阈值**：类型匹配即候选；是否真正注入由长度上限决定——`MAX_RUNBOOK_SECTION_CHARS`（默认 4000）
   超出时**整条丢弃**低排名条目（不截断正文）；
5. 无命中时注入显式空标记 `（本次未检索到相关知识条目）`，让模型知道"确实没有知识"，而不是以为被省略；
6. 信号阈值严格复用项目既有定义：`pending_count > 1000`、心跳 `> 30000ms`、成功消费心跳 `> 60000ms`；
   项目没有阈值语义的字段（如堆内存）**不造信号**。

### RAG 降级边界

| 场景 | 行为 |
|---|---|
| KB 目录缺失 / 不可读 / 无 YAML / 全部条目非法 | `rag_ready=false` + WARN，**服务照常启动**并退化 no-RAG |
| 单条知识非法（语法 / schema / 未知信号 / 重复 id） | 跳过该条（计入 `invalid_runbook_count`），其余可用 |
| `RAG_ENABLED=false` | 完全不读 KB，也不做检索 |
| 请求期检索抛异常 | 捕获 + WARN，本次诊断退化为 no-RAG（**仍只有一次 DeepSeek 调用**） |
| 知识段落超长 | 按排名整条丢弃低排名 Runbook |

`GET /healthz` 暴露 `rag_enabled` / `rag_ready` / `runbook_count` / `invalid_runbook_count`（只报状态与计数，不返回知识内容）。

### RAG 相关配置

| 变量 | 默认 | 说明 |
|---|---|---|
| `RAG_ENABLED` | `true` | 关闭后完全不读 KB |
| `RUNBOOKS_DIR` | `runbooks` | 相对服务根目录或绝对路径 |
| `MAX_RUNBOOK_SECTION_CHARS` | `4000` | 知识段落总长度上限（超出整条丢弃） |

## 测试

```powershell
.venv\Scripts\python -m pytest              # 离线：契约/ Prompt / path / 编排 / HTTP / Runbook KB / RAG 降级
.venv\Scripts\python -m pytest -m live      # 真实 DeepSeek（仅本机存在 DEEPSEEK_API_KEY 时；否则 skip）
```

> 测试用临时 KB 目录位于服务目录下的 `.pytest-tmp/`（已 gitignore）：沙箱环境不允许写系统临时区。

`tests/fixtures/` 中的样本由 V2-2 真实 smoke 捕获后**脱敏**生成
（业务 id 改号、时间与 stream id 替换为合成值），保留结构与语义，不含本地运行细节。
