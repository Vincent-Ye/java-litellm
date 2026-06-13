**English** | [中文](DESIGN.zh.md)

# java-litellm — Architecture Design

> A Java rewrite of the open-source parts of [LiteLLM](https://github.com/BerriAI/litellm) (the MIT-licensed SDK + Router + Proxy gateway).
> Version: v0.1 (design draft) Date: 2026-06-12

---

## 1. Project overview

### 1.1 What LiteLLM is

LiteLLM is a Python-implemented "LLM unification layer". Its open-source portion has three parts:

| Component | Responsibility |
|------|------|
| **SDK (litellm core library)** | Calls 100+ model providers in one unified OpenAI-shaped API: `completion()` / `embedding()` / `image_generation()` / `transcription()` / `rerank()`, etc.; unified exception mapping, streaming, cost calculation, token counting |
| **Router** | Load balancing, retries, fallback, cooldown across deployments; concurrency- and budget-aware routing |
| **Proxy (LLM gateway)** | Exposes an OpenAI-compatible REST API; virtual key management, budgets + rate limits (TPM/RPM), team/user management, caching, callback logging (Langfuse/Prometheus/OTel, etc.), Guardrails, pass-through endpoints |

Note: parts of LiteLLM's Proxy feature set (SSO, audit logs, some enterprise Guardrails) are enterprise-only and are **out of scope for this rewrite**.

### 1.2 Project goals

1. **SDK**: a Java library with no heavyweight dependencies, calling mainstream LLM providers in one OpenAI-shaped API; sync, async and streaming.
2. **Router**: a routing layer decoupled from the SDK, supporting multiple load-balancing strategies, retries, fallback and cooldown.
3. **Proxy**: a Spring Boot OpenAI-compatible gateway with virtual keys, budgets, rate limiting, caching and observability.
4. **Engineering quality**: comprehensive contract tests (aligned with the OpenAI API spec), mocked integration tests, clean SPI extension points.

### 1.3 Non-goals (not in v1.0)

- Enterprise features: SSO/SAML, audit logs, JWT team auth, enterprise Guardrails.
- Admin UI — v1.0 ships only the management REST API; UI is in v1.x.
- Aligning with all of LiteLLM's 100+ providers — v1.0 focuses on Tier-1 providers (see §4.2); the long tail is exposed via SPI for community extension.
- Fine-tuning, Batch API, Assistants API and other low-traffic endpoints.

---

## 2. Tech choices

| Dimension | Choice | Rationale |
|------|------|------|
| Language / JDK | **Java 21 LTS** | Virtual threads (Loom) suit the high-concurrency IO-bound gateway workload natively; `record`/`sealed` model API types cleanly |
| Build | **Maven multi-module** | Mature ecosystem, good IDE support, standard publishing path to Maven Central |
| HTTP client (SDK) | **JDK `java.net.http.HttpClient`** | SDK core stays free of third-party HTTP deps, reducing version conflicts on the user side; native HTTP/2 and async support |
| JSON | **Jackson** | De-facto standard; OpenAI format has many polymorphic fields (e.g. `content` can be a string or an array), Jackson's polymorphic deserialization is the best fit |
| Logging | **SLF4J** (SDK only depends on the API) | No implementation lock-in |
| Proxy framework | **Spring Boot 3.3+** (with virtual threads enabled) | Most mature ecosystem / hiring pool / ops know-how; SSE, security and configuration come out of the box |
| Persistence | **PostgreSQL + Flyway + Spring Data JDBC** | Matches LiteLLM's Postgres choice; Spring Data JDBC is more controllable than JPA |
| Cache / rate limiting | **Redis (Lettuce) + in-process Caffeine** | Two-tier cache; Redis also carries the distributed rate-limit counters |
| Observability | **Micrometer + OpenTelemetry** | One set of instrumentation, two outputs (Prometheus metrics + OTel traces) |
| Testing | **JUnit 5 + WireMock + Testcontainers** | WireMock mocks provider APIs; Testcontainers runs Postgres/Redis integration tests |

**Streaming API style decision**: the SDK's streaming surface does not bring in Reactor/RxJava — those are contagious dependencies. Three forms are exposed:
1. Synchronous: `Stream<ChatChunk>` (virtual thread + SSE parser underneath)
2. Callback: `StreamHandler` (`onChunk`/`onComplete`/`onError`)
3. Async: `CompletableFuture<ChatResponse>` + `Flow.Publisher<ChatChunk>` (the JDK's built-in Reactive Streams API, bridgeable to Reactor at zero cost)

---

## 3. Module layout (Maven Reactor)

```
java-litellm/
├── litellm-core          # canonical types, exception hierarchy, token counting, cost calc (zero HTTP deps)
├── litellm-client        # entry-point facade LiteLlm / LiteLlmClient, SSE parsing, retry plumbing
├── litellm-providers/    # provider adapters (one submodule each, opt in as needed)
│   ├── provider-openai       # also covers OpenAI-compatible providers (DeepSeek, Groq, Together, Ollama, ...)
│   ├── provider-anthropic
│   ├── provider-azure-openai
│   ├── provider-gemini       # Google AI Studio + Vertex AI
│   ├── provider-bedrock
│   └── provider-mistral
├── litellm-router        # load balancing, fallback, cooldown
├── litellm-cache         # cache abstraction + Caffeine/Redis impls
├── litellm-callbacks     # callback / logging abstraction + Langfuse/OTel/Prometheus adapters
├── litellm-proxy         # Spring Boot gateway application
└── litellm-bom           # dependency version alignment
```

Dependency direction (strictly one-way):

```
proxy ──► router ──► client ──► providers/* ──► core
  │          │                                    ▲
  └──► cache/callbacks ──────────────────────────┘
```

---

## 4. Core abstractions

### 4.1 Canonical types (litellm-core)

Everything is modeled against the **OpenAI Chat Completions format** as the canonical form, using `record` + `sealed`:

```java
public record ChatRequest(
    String model,                      // "anthropic/claude-sonnet-4-6" — provider/model shape
    List<Message> messages,
    Double temperature, Integer maxTokens, Boolean stream,
    List<Tool> tools, ToolChoice toolChoice,
    ResponseFormat responseFormat,
    Map<String, Object> extraParams    // pass-through for provider-specific params (mirrors litellm's drop_params semantics)
) {}

public sealed interface Content permits TextContent, ImageContent, AudioContent {}

public record ChatResponse(
    String id, String model, List<Choice> choices,
    Usage usage,                       // prompt/completion/total tokens + breakdown (cached, reasoning)
    BigDecimal costUsd                 // SDK computes cost up front, aligned with litellm's completion_cost
) {}
```

**Model route strings** follow LiteLLM's convention: `"openai/gpt-4o"`, `"anthropic/claude-sonnet-4-6"`, `"bedrock/us.anthropic.claude..."`. No prefix means OpenAI.

### 4.2 Provider SPI (litellm-providers)

```java
public interface LlmProvider {
    String name();                                        // "anthropic"
    Set<Capability> capabilities();                       // CHAT, STREAMING, EMBEDDING, VISION, TOOLS...

    ChatResponse chat(ChatRequest req, ProviderConfig cfg);
    void chatStream(ChatRequest req, ProviderConfig cfg, StreamHandler handler);
    EmbeddingResponse embedding(EmbeddingRequest req, ProviderConfig cfg);
    // image / audio etc. are default methods that throw UnsupportedCapabilityException
}
```

- Auto-discovered via `ServiceLoader`; whichever provider modules are on the classpath are the providers that work.
- Each provider's internal shape is a **three-step pure function**: `transformRequest` (OpenAI format → provider format) → HTTP call → `transformResponse` (provider format → OpenAI format). Transformers are separated from HTTP for unit-testability.
- **v1.0 Tier-1 providers**: OpenAI (incl. compatible endpoints), Anthropic, Azure OpenAI, Gemini (AI Studio + Vertex), AWS Bedrock, Mistral. OpenAI-compatible providers (DeepSeek / Groq / Together / Ollama / vLLM, etc.) work through `provider-openai`'s `apiBase` override — one module covers the long tail.

### 4.3 Exception hierarchy

Semantics aligned with LiteLLM's exception mapping (every provider error is mapped to an OpenAI-shaped exception):

```java
LiteLlmException (carries provider, model, statusCode, retryable)
├── AuthenticationException   (401)
├── PermissionDeniedException (403)
├── NotFoundException         (404)
├── BadRequestException       (400)  ├── ContextWindowExceededException
├── RateLimitException        (429)  // retryable
├── InternalServerException   (500)  // retryable
├── ServiceUnavailableException(503) // retryable
└── ApiTimeoutException              // retryable
```

The `retryable` flag drives client retries and the router's cooldown / fallback decisions.

### 4.4 Cost & token counting

- Model prices reuse LiteLLM's community-maintained `model_prices_and_context_window.json` (a continually updated community asset). A snapshot is bundled in the jar; runtime refresh from a remote URL plus local overrides are supported.
- Token counting: `jtokkit` (the Java port of tiktoken) gives exact counts for OpenAI-family models; for other providers, the response `usage` field is preferred, with approximate counting as a fallback when `usage` isn't reported — same behavior as LiteLLM.

### 4.5 SDK facade (litellm-client)

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

Built-in cross-cutting concerns: timeouts, exponential-backoff retries (retryable exceptions only), callback hooks (`litellm-callbacks` pre/post/failure), redacted-sensitive-data logging.

---

## 5. Router design (litellm-router)

Core concepts mirror LiteLLM's: one **model group** (e.g. `"gpt-4o"`) hosts multiple **deployments** (concrete instances behind different API keys / regions / providers).

```java
public record Deployment(
    String id, String modelGroup,
    ChatRequestDefaults litellmParams,   // actual model, apiBase, apiKey
    Integer tpm, Integer rpm, BigDecimal inputCostOverride
) {}
```

**Routing strategies (pluggable Strategy interface)**:
1. `simple-shuffle` (default): weighted random
2. `least-busy`: picks the deployment with the fewest in-flight requests
3. `latency-based`: picks the lowest mean latency in the sliding window
4. `usage-based`: picks by TPM/RPM headroom (counters in Redis or in-process)

**Reliability machinery**:
- **Retries**: retry the same deployment N times on retryable exceptions.
- **Cooldown**: when a single deployment exceeds a failure threshold inside a window → eject from the candidate pool for X seconds (default 5s; 429 honors `Retry-After`).
- **Fallback**: model-group chain, e.g. `gpt-4o → [azure-gpt-4o, claude-sonnet]`. Configurable per exception type — `ContextWindowExceeded` can have its own fallback to a long-context model.
- **Timeout budget**: the whole fallback chain shares one total deadline.

Router state (in-flight counts, latency window, cooldown list, TPM/RPM counters) is abstracted as `RouterStateStore` with both in-process and Redis impls — single-machine SDK uses in-process; multi-replica Proxy uses Redis.

---

## 6. Proxy design (litellm-proxy)

### 6.1 Outward endpoints (OpenAI compatible)

| Endpoint | Notes |
|------|------|
| `POST /v1/chat/completions` | Core; supports SSE streaming |
| `POST /v1/completions` / `/v1/embeddings` | Text completion / embeddings |
| `POST /v1/images/generations`, `/v1/audio/*` | v1.x |
| `GET /v1/models` | Models filtered by key permissions |
| `GET /health`, `/health/liveness`, `/health/readiness` | Health probes |
| `/anthropic/*` etc. pass-through | v1.x |

### 6.2 Management endpoints

`/key/generate|update|delete|info`, `/user/*`, `/team/*`, `/model/*`, `/spend/*` — semantics aligned with LiteLLM Proxy's management API for easy migration.

### 6.3 Request pipeline

```
AuthFilter (virtual-key check) → BudgetCheck → RateLimitCheck (TPM/RPM) → CacheLookup
  → Router.route() → Provider call → Response
  → Async post-processing (cost accounting / spend logs / callbacks / cache write-back)
```

- Virtual keys: random `sk-`-prefixed strings; the DB only stores their **SHA-256 hash**. A key carries a budget (`max_budget` / `duration` rolling reset), a model whitelist, TPM/RPM limits and an expiry time.
- Rate limiting: Redis sliding window (atomic Lua scripts), layered Key → User → Team → global, checked layer by layer.
- Cost accounting runs on an async queue (in-process queue + batched flush to `spend_logs`), off the request hot path.
- Caching: exact cache key = `hash(model + messages + relevant params)`; semantic caching is v1.x.

### 6.4 Configuration

Compatible with LiteLLM's `config.yaml` mental model (`model_list` + `litellm_settings` + `general_settings`), mapped via Spring's `@ConfigurationProperties`. Supports `os.environ/KEY` references for environment variables — lowers migration friction.

### 6.5 Database schema (core tables)

`virtual_keys`, `users`, `teams`, `budgets`, `spend_logs` (monthly-partitioned), `model_deployments` (dynamic model configuration with API hot-reload).

---

## 7. Observability (litellm-callbacks)

```java
public interface LlmCallback {
    void onRequest(CallContext ctx);
    void onSuccess(CallContext ctx, ChatResponse resp);
    void onFailure(CallContext ctx, LiteLlmException e);
    void onStreamComplete(CallContext ctx, List<ChatChunk> chunks); // aggregated callback
}
```

- v1.0 built-in implementations: structured-JSON logging; Micrometer/Prometheus metrics (QPS, latency quantiles, token usage, cost, error rate — tagged by model/provider/key); OpenTelemetry traces.
- v1.x: Langfuse, custom Webhook.
- All callbacks run asynchronously on virtual threads; callback exceptions do not affect the request path.

---

## 8. Testing strategy

1. **Transformer unit tests**: each provider's request/response transformer is a pure function — tested with golden cases built from real fixture JSON taken from LiteLLM's repo.
2. **WireMock integration tests**: mocked provider APIs (incl. SSE streams, 429/500 error injection) cover retry / fallback / cooldown paths.
3. **Contract tests**: pointing the official OpenAI Java SDK at our Proxy as a client guarantees "OpenAI compatible" is true.
4. **Testcontainers**: Postgres + Redis integration tests for keys / budgets / rate limiting.
5. **Real-provider smoke** (optional CI job): runs a minimal scenario when real keys are supplied as environment variables.

---

## 9. Key risks and mitigations

| Risk | Mitigation |
|------|------|
| Provider APIs keep evolving — high adapter maintenance cost | Pure-function transformers + fixture-driven tests, so upgrades touch only the transformer layer; the price table is externalized and hot-reloadable |
| Modeling OpenAI's polymorphic fields cleanly in a strongly-typed language is hard | Sealed interface + Jackson polymorphism; `extraParams` is the safety net for fields not modeled yet |
| Streaming + virtual threads under high concurrency: backpressure | The SSE writer uses a bounded queue; load testing is an M5 acceptance criterion |
| Behavior-detail mismatch with LiteLLM creates migration friction | Management API + config-file semantics aligned; a "differences from LiteLLM" doc is maintained continuously |
| Scope creep (100+ providers temptation) | SPI is open to the community; the core team maintains only the Tier-1 set |
