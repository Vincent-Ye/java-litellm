[English](ROADMAP.md) | **中文**

# java-litellm 开发路线图

> 配套 [DESIGN.md](DESIGN.zh.md)。里程碑按依赖顺序排列，每个里程碑有明确的可验收交付物。
> 工期按 1–2 名全职工程师估算；M2 起 provider 适配可并行加人提速。

---

## 总览

```
M0 脚手架 ─► M1 SDK 核心 ─► M2 供应商扩展 ─► M3 Router ─► M4 Proxy MVP ─► M5 网关增强 ─► M6 v1.0 发布
 (1周)        (3周)           (4周)            (3周)         (4周)           (4周)          (2周)
```

总计约 **21 周**（5 个月）到 v1.0。每个里程碑结束发一个 `0.x` 预览版到内部仓库，M4 起对外发 Maven Central snapshot。

---

## M0 — 项目脚手架（第 1 周）

**交付物**
- [ ] Maven 多模块骨架（core / client / provider-openai / router / cache / callbacks / proxy / bom）
- [ ] CI：GitHub Actions（build + test + spotless/checkstyle + jacoco 覆盖率门禁）
- [ ] 工程规范：Java 21、错误提示语言、commit 约定、CONTRIBUTING.md

**验收**：`mvn verify` 全绿；空模块互相依赖方向符合设计 §3。

---

## M1 — SDK 核心 + 首批两个供应商（第 2–4 周）

**范围**
- [ ] `litellm-core`：OpenAI 格式统一类型（ChatRequest/Response、Message、Content 多态、Tool、Usage）、异常体系、模型字符串解析（`provider/model`）
- [ ] `litellm-client`：`LiteLlm` 门面、JDK HttpClient 封装、超时与指数退避重试、SSE 解析器
- [ ] `provider-openai`：chat（同步/异步/流式）、tools、vision、embeddings；`apiBase` 覆盖机制（为 OpenAI 兼容系铺路）
- [ ] `provider-anthropic`：chat 全形态、tools、vision；system prompt / max_tokens 必填等差异处理
- [ ] 成本计算：内置价格表快照 + `completionCost()`；jtokkit Token 计数

**验收**
- 同一段调用代码只改 model 字符串即可在 OpenAI / Anthropic 间切换，流式与工具调用可用
- 转换器 fixture 测试 ≥ 30 个黄金用例；WireMock 覆盖 429/500/超时重试路径

**风险点**：Content 多态建模返工 → M1 第一周先冻结类型设计，写 fixture 验证后再铺量。

---

## M2 — 供应商矩阵 + 能力补全（第 5–8 周，可并行）

**范围**
- [ ] `provider-azure-openai`（api-version、deployment 名映射）
- [ ] `provider-gemini`（AI Studio + Vertex AI 双认证）
- [ ] `provider-bedrock`（SigV4 签名、Converse API）
- [ ] `provider-mistral`
- [ ] OpenAI 兼容系验证：DeepSeek、Groq、Ollama、vLLM（文档 + 冒烟测试，不单独建模块）
- [ ] 能力补全：`/embeddings` 全供应商对齐、reasoning 模型参数（thinking/reasoning_effort）、结构化输出（response_format/json_schema）
- [ ] 价格表远端刷新 + 本地覆盖机制

**验收**：6 个 Tier-1 供应商通过统一的「能力矩阵测试套件」（同一套测试参数化跑所有 provider）；能力矩阵文档自动生成。

---

## M3 — Router（第 9–11 周）

**范围**
- [ ] Deployment / model group 模型；YAML 与编程式两种配置
- [ ] 四种路由策略：simple-shuffle、least-busy、latency-based、usage-based
- [ ] 重试 → cooldown → fallback 全链路；按异常类型的 fallback 规则（含 context-window fallback）
- [ ] `RouterStateStore`：进程内实现 + Redis 实现
- [ ] 整链路总超时预算

**验收**：WireMock 故障注入测试——单部署连续 429 触发 cooldown、组内无可用部署触发跨组 fallback、恢复后自动回池；1000 并发虚拟线程压测无状态竞争问题。

---

## M4 — Proxy MVP（第 12–15 周）

**范围**
- [ ] Spring Boot 应用骨架（虚拟线程、优雅停机、健康检查三件套）
- [ ] `POST /v1/chat/completions`（含 SSE）、`/v1/embeddings`、`GET /v1/models`
- [ ] `config.yaml` 加载（兼容 LiteLLM 的 model_list 语法、`os.environ/` 引用）
- [ ] 虚拟 Key：`/key/generate|info|update|delete`，SHA-256 存储，模型白名单，过期时间
- [ ] Postgres + Flyway schema；spend_logs 异步记账
- [ ] Docker 镜像 + docker-compose（proxy + postgres + redis）一键起

**验收**：用 **OpenAI 官方 Java/Python SDK** 指向 Proxy 完成 chat/stream/embeddings 契约测试；非法 Key 401、越权模型 403；记账金额与 SDK `completionCost()` 一致。

---

## M5 — 网关增强（第 16–19 周）

**范围**
- [ ] 预算体系：Key/User/Team 三级 max_budget、滚动周期重置、超额拒绝
- [ ] 限流：Redis 滑动窗口 TPM/RPM，Key → Team → 全局逐级校验
- [ ] 缓存：Caffeine + Redis 双层精确缓存（含流式响应缓存回放）
- [ ] 可观测性：Prometheus 指标全集（时延分位/token/成本/错误率，按 model/key 维度）、OTel trace、结构化日志
- [ ] 管理 API 补全：`/user/*`、`/team/*`、`/spend/*`、`/model/*`（动态增删模型，热生效）
- [ ] Proxy 内嵌 Router（多 deployment 负载均衡 + fallback 在网关层生效）

**验收**：多副本 Proxy + Redis 部署下限流/预算计数准确（并发压测误差 < 1%）；Grafana 示例看板；缓存命中率指标可见。

---

## M6 — 加固与 v1.0 发布（第 20–21 周）

**范围**
- [ ] 性能压测与调优（目标：网关层 P99 自身开销 < 15ms，不含上游）
- [ ] 安全审查：Key 泄漏路径、日志脱敏、依赖 CVE 扫描
- [ ] 文档站：快速开始、迁移指南（《从 LiteLLM Proxy 迁移》）、能力矩阵、与 LiteLLM 的已知差异
- [ ] Maven Central 正式发布 + Docker Hub 镜像 + GitHub Release

**验收**：新用户按文档 15 分钟内完成「SDK 调用 + Proxy 部署 + Key 签发」全流程。

---

## v1.x 后续规划（不阻塞 v1.0）

| 主题 | 内容 |
|------|------|
| 端点扩展 | `/v1/images/*`、`/v1/audio/*`、`/v1/responses`、rerank、pass-through 透传端点 |
| 语义缓存 | 基于 embedding 相似度的缓存命中 |
| Guardrails | 内容安全钩子框架（pre/post-call guardrail SPI）+ 开源 guardrail 适配 |
| 回调生态 | Langfuse、Webhook、S3/GCS 日志导出 |
| Admin UI | 管理前端（Key/预算/用量看板） |
| 供应商长尾 | 社区 SPI 贡献流程 + provider 认证测试套件 |

---

## 持续性事项（贯穿所有里程碑）

- **价格表同步**：每月同步上游 `model_prices_and_context_window.json` 快照。
- **上游跟踪**：关注 LiteLLM 上游的 API 语义变化（管理端点、config 语法），差异记入文档。
- **每里程碑回顾**：验收未达标项显式降级或排期，不带病进入下一阶段。
