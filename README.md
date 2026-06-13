**English** | [中文](README.zh.md)

# java-litellm

A Java rewrite of the open-source parts of [LiteLLM](https://github.com/BerriAI/litellm): unified LLM SDK + Router + OpenAI-compatible Proxy gateway.

**Status: M0–M6 complete.** The codebase is functionally done; first Maven Central release is pending namespace verification and the operator's GPG/Sonatype credentials. Six providers (OpenAI incl. compatible endpoints, Anthropic, Azure OpenAI, Mistral, Gemini, AWS Bedrock), virtual keys with team/user hierarchy, cascading budgets and rate limits, in-process and Redis-backed cache + rate limiter + router state, Prometheus metrics, dynamic model management with hot-reload, Docker deployment. See the [release guide](docs/RELEASING.md).

```java
LiteLlm client = LiteLlm.builder()
        .apiKey("anthropic", System.getenv("ANTHROPIC_API_KEY"))
        .build();
ChatResponse resp = client.chat(ChatRequest.builder()
        .model("anthropic/claude-sonnet-4-6")
        .message(Message.user("Hello!"))
        .build());
System.out.println(resp.firstText() + " (cost $" + resp.costUsd() + ")");
```

## License

MIT — see [LICENSE](LICENSE). The bundled price table is sourced from [BerriAI/litellm](https://github.com/BerriAI/litellm) (also MIT); see [NOTICE](NOTICE) for attribution.

## Docs

- [Getting started](docs/GETTING_STARTED.md) — SDK and Proxy quickstarts
- [Migration guide](docs/MIGRATION.md) — moving from LiteLLM (Python) and known differences
- [Capability matrix](docs/CAPABILITIES.md) — what each provider supports
- [Architecture design](docs/DESIGN.md) — scope, tech choices, module layout, core abstractions, test strategy
- [Roadmap](docs/ROADMAP.md) — M0–M6 milestones with deliverables and acceptance criteria
- [Releasing](docs/RELEASING.md) — one-time setup + per-release runbook for Maven Central

## One-line architecture

```
litellm-core (canonical OpenAI-format types)
   ▲
litellm-providers (OpenAI✓ / Anthropic✓ / Azure✓ / Mistral✓ / Gemini✓ / Bedrock✓, extensible via SPI)
   ▲
litellm-client (SDK facade: sync/async/streaming + retries + cost calc)
   ▲
litellm-router (load balancing / fallback / cooldown)
   ▲
litellm-proxy (Spring Boot gateway: virtual keys / budgets+limits / cache / observability)
```

Stack: Java 21 (virtual threads) · Maven multi-module · Jackson · Spring Boot 3 · PostgreSQL · Redis (optional).
