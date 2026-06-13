# java-litellm

用 Java 重写 [LiteLLM](https://github.com/BerriAI/litellm) 的开源部分：统一 LLM SDK + Router + OpenAI 兼容 Proxy 网关。

**当前阶段：M3 Router 完成——四种负载均衡策略（simple-shuffle / least-busy / latency-based / usage-based）、跨部署重试、429 即时冷却、fallback 与 context-window fallback 链、总超时预算，1000 并发压测通过。供应商能力见[能力矩阵](docs/CAPABILITIES.md)。下一步：M4 Proxy MVP。**

```java
Router router = Router.builder()
        .deployment(Deployment.builder().id("azure-eastus").modelGroup("gpt-4o")
                .model("azure/my-gpt4o").config(azureCfg).build())
        .deployment(Deployment.builder().id("openai-main").modelGroup("gpt-4o")
                .model("openai/gpt-4o").config(openaiCfg).build())
        .strategy(RoutingStrategy.latencyBased())
        .fallback("gpt-4o", List.of("claude-sonnet"))
        .build();
ChatResponse resp = router.chat(req); // 自动负载均衡 + 故障转移
```

```java
LiteLlm client = LiteLlm.builder()
        .apiKey("openai", System.getenv("OPENAI_API_KEY"))
        .apiKey("anthropic", System.getenv("ANTHROPIC_API_KEY"))
        .build();

// 只改 model 字符串即可切换供应商
ChatResponse resp = client.chat(ChatRequest.builder()
        .model("anthropic/claude-sonnet-4-6")
        .message(Message.user("Hello"))
        .build());
System.out.println(resp.firstText() + "  cost=$" + resp.costUsd());

// 流式
client.chatStream(req).forEach(chunk -> System.out.print(chunk.textDelta()));
```

## 文档

- [架构设计](docs/DESIGN.md) — 范围界定、技术选型、模块划分、核心抽象（SDK / Router / Proxy）、测试策略与风险
- [开发路线图](docs/ROADMAP.md) — M0–M6 共约 21 周到 v1.0，每个里程碑含交付物与验收标准

## 一句话架构

```
litellm-core (统一 OpenAI 格式类型)
   ▲
litellm-providers (OpenAI✓ / Anthropic✓ / Azure✓ / Mistral✓ / Gemini✓ / Bedrock✓, SPI 可扩展)
   ▲
litellm-client (SDK 门面: 同步/异步/流式 + 重试 + 成本计算)
   ▲
litellm-router (负载均衡 / fallback / cooldown)
   ▲
litellm-proxy (Spring Boot 网关: 虚拟 Key / 预算限流 / 缓存 / 可观测性)
```

技术栈：Java 21（虚拟线程）· Maven 多模块 · Jackson · Spring Boot 3 · PostgreSQL · Redis。
