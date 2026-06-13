# 快速开始

两种用法：作为 **Java SDK** 直接嵌入应用，或作为 **Proxy 网关**对外提供 OpenAI 兼容 API。

---

## 一、SDK

### 依赖

```xml
<dependency>
  <groupId>io.github.vincentye</groupId>
  <artifactId>litellm-client</artifactId>
  <version>0.1.0</version>
</dependency>
<!-- 按需引入供应商，classpath 上有哪个就支持哪个（ServiceLoader 自动发现） -->
<dependency>
  <groupId>io.github.vincentye</groupId>
  <artifactId>provider-openai</artifactId>
  <version>0.1.0</version>
</dependency>
<dependency>
  <groupId>io.github.vincentye</groupId>
  <artifactId>provider-anthropic</artifactId>
  <version>0.1.0</version>
</dependency>
```

### 同步调用

```java
LiteLlm client = LiteLlm.builder()
        .apiKey("openai", System.getenv("OPENAI_API_KEY"))
        .apiKey("anthropic", System.getenv("ANTHROPIC_API_KEY"))
        .build();

ChatResponse resp = client.chat(ChatRequest.builder()
        .model("anthropic/claude-sonnet-4-6")   // 只改前缀即可切换供应商
        .message(Message.system("Be concise."))
        .message(Message.user("Hello!"))
        .build());

System.out.println(resp.firstText());
System.out.println("cost = $" + resp.costUsd());   // SDK 直接算好成本
```

### 流式

```java
// 形态 1：惰性 Stream
client.chatStream(req).forEach(chunk -> System.out.print(chunk.textDelta()));

// 形态 2：回调
client.chatStream(req, new StreamHandler() {
    public void onChunk(ChatChunk chunk) { System.out.print(chunk.textDelta()); }
    public void onComplete() { System.out.println(); }
});

// 形态 3：异步
CompletableFuture<ChatResponse> future = client.chatAsync(req);
```

### 工具调用

```java
ChatRequest req = ChatRequest.builder()
        .model("openai/gpt-4o")
        .message(Message.user("What's the weather in Tokyo?"))
        .tools(List.of(new Tool("get_weather", "look up weather", schemaJsonNode)))
        .toolChoice(ToolChoice.AUTO)
        .build();

ChatResponse resp = client.chat(req);
for (ToolCall call : resp.choices().getFirst().message().toolCalls()) {
    // call.name(), call.arguments() (JSON 字符串)
}
```

### 路由与降级（Router）

```java
Router router = Router.builder()
        .deployment(Deployment.builder().id("openai-main").modelGroup("gpt-4o")
                .model("openai/gpt-4o").config(openaiCfg).build())
        .deployment(Deployment.builder().id("azure-eastus").modelGroup("gpt-4o")
                .model("azure/my-gpt4o-deployment").config(azureCfg).build())
        .strategy(RoutingStrategy.latencyBased())
        .fallback("gpt-4o", List.of("claude-sonnet"))               // 组级降级
        .contextWindowFallback("gpt-4o", List.of("gpt-4o-long"))    // 超上下文降级
        .build();

ChatResponse resp = router.chat(req);   // 自动负载均衡 + 故障转移 + 冷却
```

---

## 二、Proxy 网关

### Docker 一键起

```bash
cp config.example.yaml config.yaml      # 填入你的供应商 Key
export LITELLM_MASTER_KEY=sk-master-改我
export OPENAI_API_KEY=sk-...
docker compose up -d                    # 启动 proxy + postgres
```

`config.yaml`（与 LiteLLM 的 `config.yaml` 语义一致）：

```yaml
model_list:
  - model_name: gpt-4o
    litellm_params:
      model: openai/gpt-4o
      api_key: os.environ/OPENAI_API_KEY
  - model_name: claude-sonnet
    litellm_params:
      model: anthropic/claude-sonnet-4-6
      api_key: os.environ/ANTHROPIC_API_KEY
litellm_settings:
  cache: true
  redis_url: os.environ/REDIS_URL    # 可选；多副本部署时启用 Redis 后端
general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
```

> **单副本 vs 多副本**：默认无 `redis_url` 时，缓存/限流/Router 状态走进程内（Caffeine + 内存窗口），适合单副本部署。设了 `redis_url` 后，三者全部切到 Redis（缓存 `SETEX` + Lua 原子滑动窗口），多副本之间计数共享、冷却即时同步。

### 签发虚拟 Key

```bash
curl -X POST localhost:4000/key/generate \
     -H "Authorization: Bearer $LITELLM_MASTER_KEY" \
     -d '{"key_alias":"team-a","models":["gpt-4o"],"max_budget":10.0,
          "duration":"30d","tpm_limit":100000,"rpm_limit":60}'
# => {"key":"sk-...","token_hash":"..."}
```

### 像调用 OpenAI 一样调用

```bash
curl localhost:4000/v1/chat/completions \
     -H "Authorization: Bearer sk-..." \
     -d '{"model":"gpt-4o","messages":[{"role":"user","content":"Hello"}]}'
```

任何 OpenAI SDK 把 `base_url` 指向 `http://localhost:4000` 即可，无需改代码。

### 团队 / 用户 / 动态模型

```bash
# 团队（预算与限流在多个 Key 间共享）
curl -X POST localhost:4000/team/new -H "Authorization: Bearer $MASTER" \
     -d '{"team_alias":"acme","max_budget":1000,"rpm_limit":600}'

# 运行时增删模型，立即生效，无需重启
curl -X POST localhost:4000/model/new -H "Authorization: Bearer $MASTER" \
     -d '{"model_name":"deepseek","litellm_params":{"model":"openai/deepseek-chat",
          "api_base":"https://api.deepseek.com/v1","api_key":"os.environ/DEEPSEEK_API_KEY"}}'
```

### 可观测性

- 指标：`GET /actuator/prometheus`（请求时延/token/成本/缓存命中/限流，按 model 维度打标）
- 健康检查：`GET /actuator/health`（含 liveness/readiness 探针）
- 用量报表：`GET /spend/logs`、`GET /spend/keys`（master key 限定）

完整管理端点见 [DESIGN.md](DESIGN.md) §6.2，供应商能力见 [CAPABILITIES.md](CAPABILITIES.md)。
