# V2-3 AI Diagnosis Service

接收 **V2-2 冻结的 `IncidentContext` JSON**，调用 DeepSeek 输出**结构化诊断结果**。

边界（冻结）：

- Java 仍是主系统；本服务**只读**——不连 Redis/MySQL，不执行任何运维动作，不做 RAG；
- 模型只产出**语义字段**（`diagnosis_status` / `root_cause` / `evidence(path,note)` /
  `recommended_actions(action,rationale)` / `insufficient_reason`），
  其余元数据（`observed`、`requires_human`、`evidence_validation`、`model`、时间、`error_code`）全部由本服务生成；
- 模型侧失败一律返回 **HTTP 200 + `diagnosis_status=UNAVAILABLE`**，让 Java 调用方无需为 AI 可用性写异常分支；
- V2-3 单次调用、**不做 retry/backoff**（retry / 限流 / 熔断留 V2-4）。

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
| GET | `/healthz` | 服务与配置状态（只报 `model_configured`，绝不返回凭据） |
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
  "evidence_validation": {"submitted": 2, "accepted": 2, "dropped": 0},
  "model": "deepseek-chat",
  "prompt_version": "v2-3.1",
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

## 测试

```powershell
.venv\Scripts\python -m pytest              # 离线：契约/ Prompt / path / 编排 / HTTP
.venv\Scripts\python -m pytest -m live      # 真实 DeepSeek（仅本机存在 DEEPSEEK_API_KEY 时；否则 skip）
```

`tests/fixtures/` 中的样本由 V2-2 真实 smoke 捕获后**脱敏**生成
（业务 id 改号、时间与 stream id 替换为合成值），保留结构与语义，不含本地运行细节。
