# java-litellm 架构设计文档

> 用 Java 重写 [LiteLLM](https://github.com/BerriAI/litellm) 的开源部分（MIT 许可范围内的 SDK + Router + Proxy 网关）。
> 版本：v0.1（设计稿） 日期：2026-06-12

---

## 1. 项目概述

### 1.1 LiteLLM 是什么

LiteLLM 是一个 Python 实现的「LLM 统一接入层」，开源部分包含三大块：

| 组件 | 职责 |
|------|------|
| **SDK（litellm 核心库）** | 用统一的 OpenAI 格式调用 100+ 模型供应商：`completion()` / `embedding()` / `image_generation()` / `transcription()` / `rerank()` 等；统一异常映射、流式输出、成本计算、Token 计数 |
| **Router** | 多部署（deployment）之间的负载均衡、重试、降级（fallback）、冷却（cooldown）、并发/预算感知路由 |
| **Proxy（LLM Gateway）** | 对外暴露 OpenAI 兼容的 REST API；虚拟 Key 管理、预算与限流（TPM/RPM）、团队/用户管理、缓存、回调日志（Langfuse/Prometheus/OTel 等）、Guardrails、透传端点 |

注意：LiteLLM 仓库中部分 Proxy 功能（SSO、审计日志、部分企业 Guardrails 等）属于企业版，**不在本项目重写范围内**。

### 1.2 项目目标

1. **SDK**：提供一个零重量级依赖的 Java 库，以 OpenAI 格式统一调用主流 LLM 供应商，支持同步、异步、流式三种调用方式。
2. **Router**：与 SDK 解耦的路由层，支持多种负载均衡策略、重试、fallback、冷却。
3. **Proxy**：基于 Spring Boot 的 OpenAI 兼容网关，支持虚拟 Key、预算限流、缓存、可观测性。
4. **工程质量**：完整的契约测试（与 OpenAI API 规范对齐）、Mock 集成测试、清晰的 SPI 扩展点。

### 1.3 非目标（v1.0 不做）

- 企业版功能：SSO/SAML、审计日志、JWT 团队鉴权、企业 Guardrails。
- Admin UI（管理前端）——v1.0 只提供 REST 管理 API，UI 列入 v1.x 计划。
- 对齐 LiteLLM 的全部 100+ 供应商——v1.0 聚焦 Tier-1 供应商（见 §4.2），其余通过 SPI 由社区扩展。
- 微调（fine-tuning）、Batch API、Assistants API 等低频端点。

---

## 2. 技术选型

| 维度 | 选择 | 理由 |
|------|------|------|
| 语言/JDK | **Java 21 LTS** | 虚拟线程（Loom）天然适合高并发 IO 密集的网关场景；record/sealed 适合建模 API 类型 |
| 构建 | **Maven 多模块** | 生态成熟、IDE 支持好、发布到 Maven Central 流程标准 |
| HTTP 客户端（SDK） | **JDK `java.net.http.HttpClient`** | SDK 核心保持零第三方 HTTP 依赖，降低使用方依赖冲突；原生支持 HTTP/2 与异步 |
| JSON | **Jackson** | 事实标准；OpenAI 格式存在大量多态字段（content 既可为 string 又可为数组），Jackson 的多态反序列化支持最好 |
| 日志 | **SLF4J**（SDK 只依赖 API） | 不绑定实现 |
| Proxy 框架 | **Spring Boot 3.3+**（启用虚拟线程） | 生态/招聘/运维成熟度最高；SSE、安全、配置管理开箱即用 |
| 持久化 | **PostgreSQL + Flyway + Spring Data JDBC** | 对齐 LiteLLM 的 Postgres 选型；Spring Data JDBC 比 JPA 更可控 |
| 缓存/限流 | **Redis（Lettuce）+ 进程内 Caffeine** | 双层缓存；Redis 同时承担分布式限流计数 |
| 可观测性 | **Micrometer + OpenTelemetry** | Prometheus 指标与 OTel Trace 一套埋点两种输出 |
| 测试 | **JUnit 5 + WireMock + Testcontainers** | WireMock 模拟各供应商 API；Testcontainers 跑 Postgres/Redis 集成测试 |

**流式 API 风格决策**：SDK 流式接口不引入 Reactor/RxJava，避免传染性依赖。对外暴露三种形态：
1. 同步：`Stream<ChatChunk>`（底层虚拟线程 + SSE 解析）
2. 回调：`StreamHandler`（onChunk/onComplete/onError）
3. 异步：`CompletableFuture<ChatResponse>` + `Flow.Publisher<ChatChunk>`（JDK 自带 Reactive Streams 接口，可被 Reactor 零成本桥接）

---

## 3. 模块划分（Maven Reactor）

```
java-litellm/
├── litellm-core          # 统一类型、异常体系、Token 计数、成本计算（零 HTTP 依赖）
├── litellm-client        # 入口门面 LiteLlm / LiteLlmClient，SSE 解析，重试基础设施
├── litellm-providers/    # 供应商适配器（每个供应商一个子模块，按需引入）
│   ├── provider-openai       # 同时覆盖 OpenAI 兼容系（DeepSeek、Groq、Together、Ollama...）
│   ├── provider-anthropic
│   ├── provider-azure-openai
│   ├── provider-gemini       # Google AI Studio + Vertex AI
│   ├── provider-bedrock
│   └── provider-mistral
├── litellm-router        # 负载均衡、fallback、cooldown
├── litellm-cache         # 缓存抽象 + Caffeine/Redis 实现
├── litellm-callbacks     # 回调/日志抽象 + Langfuse/OTel/Prometheus 适配
├── litellm-proxy         # Spring Boot 网关应用
└── litellm-bom           # 依赖版本对齐
```

依赖方向（严格单向）：

```
proxy ──► router ──► client ──► providers/* ──► core
  │          │                                    ▲
  └──► cache/callbacks ──────────────────────────┘
```

---

## 4. 核心抽象设计

### 4.1 统一类型（litellm-core）

全部以 **OpenAI Chat Completions 格式**为规范格式（canonical format），用 record + sealed 建模：

```java
public record ChatRequest(
    String model,                      // "anthropic/claude-sonnet-4-6" 形如 provider/model
    List<Message> messages,
    Double temperature, Integer maxTokens, Boolean stream,
    List<Tool> tools, ToolChoice toolChoice,
    ResponseFormat responseFormat,
    Map<String, Object> extraParams    // 供应商特有参数透传（对齐 litellm 的 drop_params 语义）
) {}

public sealed interface Content permits TextContent, ImageContent, AudioContent {}

public record ChatResponse(
    String id, String model, List<Choice> choices,
    Usage usage,                       // prompt/completion/total tokens + 细分（cached, reasoning）
    BigDecimal costUsd                 // SDK 直接算好成本，对齐 litellm completion_cost
) {}
```

**模型路由字符串**沿用 LiteLLM 约定：`"openai/gpt-4o"`、`"anthropic/claude-sonnet-4-6"`、`"bedrock/us.anthropic.claude..."`，无前缀时按 OpenAI 处理。

### 4.2 供应商 SPI（litellm-providers）

```java
public interface LlmProvider {
    String name();                                        // "anthropic"
    Set<Capability> capabilities();                       // CHAT, STREAMING, EMBEDDING, VISION, TOOLS...

    ChatResponse chat(ChatRequest req, ProviderConfig cfg);
    void chatStream(ChatRequest req, ProviderConfig cfg, StreamHandler handler);
    EmbeddingResponse embedding(EmbeddingRequest req, ProviderConfig cfg);
    // image / audio 等能力为 default 方法，默认抛 UnsupportedCapabilityException
}
```

- 通过 `ServiceLoader` 自动发现，`classpath` 上有哪个 provider 模块就支持哪个供应商。
- 每个 Provider 内部职责 = **三段式纯函数**：`transformRequest`（OpenAI 格式 → 供应商格式）→ HTTP 调用 → `transformResponse`（供应商格式 → OpenAI 格式）。转换器与 HTTP 分离，便于单测。
- **v1.0 Tier-1 供应商**：OpenAI（含兼容系）、Anthropic、Azure OpenAI、Gemini（AI Studio + Vertex）、AWS Bedrock、Mistral。OpenAI 兼容系（DeepSeek/Groq/Together/Ollama/vLLM 等）通过 `provider-openai` 的 `apiBase` 覆盖实现，一个模块覆盖长尾。

### 4.3 异常体系

对齐 LiteLLM 的异常映射语义（所有供应商错误统一映射为 OpenAI 风格异常）：

```java
LiteLlmException (含 provider, model, statusCode, retryable)
├── AuthenticationException   (401)
├── PermissionDeniedException (403)
├── NotFoundException         (404)
├── BadRequestException       (400)  ├── ContextWindowExceededException
├── RateLimitException        (429)  // retryable
├── InternalServerException   (500)  // retryable
├── ServiceUnavailableException(503) // retryable
└── ApiTimeoutException              // retryable
```

`retryable` 标记驱动 client 重试与 router 的 cooldown/fallback 决策。

### 4.4 成本与 Token 计数

- 模型价格表沿用 LiteLLM 维护的 `model_prices_and_context_window.json`（社区资产，持续更新）。内置一份快照打进 jar，支持启动时从远端 URL 刷新 + 本地覆盖。
- Token 计数：集成 `jtokkit`（tiktoken 的 Java 实现）做 OpenAI 系精确计数；非 OpenAI 系优先使用响应 `usage` 字段，预估场景退化为近似计数（与 litellm 行为一致）。

### 4.5 SDK 门面（litellm-client）

```java
LiteLlm client = LiteLlm.builder()
    .apiKey("anthropic", System.getenv("ANTHROPIC_API_KEY"))
    .retryPolicy(RetryPolicy.exponential(3))
    .build();

ChatResponse resp = client.chat(ChatRequest.builder()
    .model("anthropic/claude-sonnet-4-6")
    .message(Message.user("Hello"))
    .build());

client.chatStream(req).forEach(chunk -> System.out.print(chunk.delta()));
```

内置横切能力：超时、指数退避重试（仅 retryable 异常）、回调埋点（`litellm-callbacks` 的 pre/post/failure hook）、敏感信息脱敏日志。

---

## 5. Router 设计（litellm-router)

核心概念对齐 LiteLLM：一个 **model group**（如 `"gpt-4o"`）下挂多个 **deployment**（不同 API Key / region / 供应商的具体部署）。

```java
public record Deployment(
    String id, String modelGroup,
    ChatRequestDefaults litellmParams,   // 实际 model、apiBase、apiKey
    Integer tpm, Integer rpm, BigDecimal inputCostOverride
) {}
```

**路由策略（Strategy 接口，可插拔）**：
1. `simple-shuffle`（默认）：加权随机
2. `least-busy`：选当前在途请求最少的部署
3. `latency-based`：滑动窗口平均时延最低者
4. `usage-based`：按 TPM/RPM 余量选择（计数器存 Redis 或进程内）

**可靠性机制**：
- **重试**：同一 deployment 上按 retryable 异常重试 N 次；
- **Cooldown**：单 deployment 在窗口期内失败率/失败次数超阈值 → 移出候选池 X 秒（默认 5 秒，429 用 `Retry-After`）；
- **Fallback**：model group 级联，如 `gpt-4o → [azure-gpt-4o, claude-sonnet]`；按异常类型可配置（如 `ContextWindowExceeded` 单独 fallback 到长上下文模型）；
- **超时预算**：整条 fallback 链共享一个总超时。

Router 状态（在途计数、时延窗口、cooldown 名单、TPM/RPM 计数）抽象为 `RouterStateStore`，提供进程内与 Redis 两个实现——单机 SDK 用进程内，Proxy 多副本部署用 Redis。

---

## 6. Proxy 设计（litellm-proxy）

### 6.1 对外端点（OpenAI 兼容）

| 端点 | 说明 |
|------|------|
| `POST /v1/chat/completions` | 核心；支持 SSE 流式 |
| `POST /v1/completions` / `/v1/embeddings` | 文本补全 / 向量 |
| `POST /v1/images/generations`、`/v1/audio/*` | v1.x |
| `GET /v1/models` | 按 Key 权限过滤的模型列表 |
| `GET /health`, `/health/liveness`, `/health/readiness` | 健康检查 |
| `/anthropic/*` 等 pass-through | 透传端点，v1.x |

### 6.2 管理端点

`/key/generate|update|delete|info`、`/user/*`、`/team/*`、`/model/*`、`/spend/*`——语义对齐 LiteLLM Proxy 的管理 API，便于已有用户迁移。

### 6.3 请求处理管线

```
AuthFilter(虚拟Key校验) → BudgetCheck → RateLimitCheck(TPM/RPM) → CacheLookup
  → Router.route() → Provider 调用 → 响应
  → 异步后处理(成本记账 / spend日志落库 / callbacks / 缓存回填)
```

- 虚拟 Key：`sk-` 前缀随机串，库中只存 **SHA-256 哈希**；Key 关联预算（max_budget / duration 滚动重置）、模型白名单、TPM/RPM 限额、过期时间。
- 限流：Redis 滑动窗口（Lua 脚本原子化），层级为 Key → User → Team → 全局，逐层校验。
- 成本记账走异步队列（进程内 queue + 批量 flush 到 `spend_logs` 表），不阻塞请求主链路。
- 缓存：精确缓存 key = `hash(model + messages + 关键参数)`；语义缓存列入 v1.x。

### 6.4 配置

兼容 LiteLLM 的 `config.yaml` 心智模型（`model_list` + `litellm_settings` + `general_settings`），用 Spring 的 `@ConfigurationProperties` 映射，支持环境变量引用 `os.environ/KEY` 语法，降低迁移成本。

### 6.5 数据库 Schema（核心表）

`virtual_keys`、`users`、`teams`、`budgets`、`spend_logs`（按月分区）、`model_deployments`（动态模型配置，支持 API 热更新）。

---

## 7. 可观测性（litellm-callbacks）

```java
public interface LlmCallback {
    void onRequest(CallContext ctx);
    void onSuccess(CallContext ctx, ChatResponse resp);
    void onFailure(CallContext ctx, LiteLlmException e);
    void onStreamComplete(CallContext ctx, List<ChatChunk> chunks); // 聚合后回调
}
```

- v1.0 内置实现：日志（结构化 JSON）、Micrometer/Prometheus 指标（QPS、时延分位、token 用量、成本、错误率，按 model/provider/key 维度打标）、OpenTelemetry trace。
- v1.x：Langfuse、自定义 Webhook。
- 回调全部在虚拟线程异步执行，回调异常不影响主链路。

---

## 8. 测试策略

1. **转换器单测**：每个 provider 的 request/response 转换是纯函数，用 LiteLLM 仓库中的真实 fixture（请求/响应 JSON 样本）做黄金用例。
2. **WireMock 集成测试**：模拟供应商 API（含 SSE 流、429/500 错误注入），覆盖重试、fallback、cooldown 路径。
3. **契约测试**：Proxy 端点用 OpenAI 官方 Java SDK 作为客户端打自己的 Proxy，保证「OpenAI 兼容」名副其实。
4. **Testcontainers**：Postgres + Redis 的 Key/预算/限流集成测试。
5. **真实供应商冒烟**（CI 可选 job）：环境变量提供真 Key 时跑最小用例。

---

## 9. 关键风险与对策

| 风险 | 对策 |
|------|------|
| 供应商 API 持续演进，适配维护成本高 | 转换器纯函数化 + fixture 驱动测试，升级时只改转换层；价格表外置可热更新 |
| OpenAI 格式多态字段在强类型语言中建模复杂 | sealed interface + Jackson 多态；`extraParams` 兜底透传未建模字段 |
| 流式 + 虚拟线程在高并发下的背压 | SSE 写出端用有界队列；压测列入 M5 验收项 |
| 与 LiteLLM 行为细节不一致导致迁移摩擦 | 管理 API/配置文件语义对齐；编写《与 LiteLLM 的差异》文档持续维护 |
| 范围蔓延（100+ 供应商诱惑） | SPI 开放给社区，核心团队只维护 Tier-1 |
