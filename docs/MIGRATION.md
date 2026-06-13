**English** | [中文](MIGRATION.zh.md)

# Migration from LiteLLM (Python)

java-litellm deliberately aligns with LiteLLM at the **config file** and **management API** layers, so most existing setups port over smoothly. This document lists what aligns and what doesn't.

---

## 1. config.yaml

The core structure of `model_list` / `litellm_params` / `general_settings` / `litellm_settings` is identical, and the `os.environ/VAR` reference syntax is preserved:

```yaml
model_list:
  - model_name: gpt-4o
    litellm_params:
      model: openai/gpt-4o
      api_key: os.environ/OPENAI_API_KEY
      api_base: https://...        # optional; OpenAI-compatible endpoints use this override
      api_version: 2024-10-21      # Azure
      aws_region_name: us-east-1   # Bedrock
      weight: 2                    # load-balancing weight
      tpm: 100000                  # deployment-level TPM/RPM (used by usage-based routing)
      rpm: 600
litellm_settings:
  cache: true
  cache_params:
    ttl: 300
general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
```

> Only the subset of fields above is supported today. Less-common `litellm_params` fields (some guardrail and router tuning knobs in LiteLLM) are not implemented yet and will be ignored silently instead of erroring on migration — cross-check against this list to confirm your key fields are covered.

## 2. Endpoint parity

| Endpoint | Status |
|------|------|
| `POST /v1/chat/completions` (incl. SSE) | ✅ |
| `POST /v1/embeddings` | ✅ |
| `GET /v1/models` | ✅ (filtered by key permissions) |
| `POST /key/generate\|info\|update\|delete` | ✅ |
| `POST /team/new\|info\|update\|delete` | ✅ |
| `POST /user/new\|info` | ✅ |
| `POST /model/new\|delete`, `GET /model/info` | ✅ (hot-reload) |
| `GET /spend/logs\|keys` | ✅ |
| `GET /health`, `/actuator/health`, `/actuator/prometheus` | ✅ |
| `POST /v1/completions`, `/v1/images/*`, `/v1/audio/*`, rerank, pass-through | ⏳ v1.x |

Virtual-key behavior matches: `sk-` prefix, SHA-256-only storage, model whitelist, `max_budget`, `duration` (`30s/30m/30h/30d`), TPM/RPM limits, expiry and blocking.

## 3. Provider prefixes

Model route strings follow the same convention as LiteLLM: `openai/...`, `anthropic/...`, `azure/...`, `gemini/...`, `bedrock/...`, `mistral/...`. A route without a prefix defaults to OpenAI. OpenAI-compatible providers (DeepSeek, Groq, Together, Ollama, vLLM, ...) work through `provider-openai` + an `api_base` override — same pattern as LiteLLM.

## 4. Known differences

1. **Provider coverage**: v1.0 focuses on the 6 Tier-1 providers (see [CAPABILITIES.md](CAPABILITIES.md)), not LiteLLM's 100+. The rest are SPI extension points for the community.
2. **Enterprise features are out of scope**: SSO/SAML, audit logs, JWT team auth, enterprise Guardrails are not implemented (these aren't pure open-source in LiteLLM either).
3. **Distributed backends**: in-process by default; setting `litellm_settings.redis_url` (or `general_settings.redis_url`) in `config.yaml` switches cache, rate limiter and router state (cooldown + latency windows) to Redis, giving accurate cross-replica counters (consistency bounded by Redis itself, not per-process drift).
4. **Token counting**: OpenAI-family models use jtokkit for exact counts; other providers prefer the response `usage` field and fall back to approximate estimation when usage is unavailable — same behavior as LiteLLM's `token_counter`.
5. **Price table**: reuses LiteLLM's `model_prices_and_context_window.json`; a snapshot is bundled in the jar. Unknown models return `null` cost (not 0) so you can distinguish "free" from "unknown".
6. **Callback ecosystem**: v1.0 ships logging / Prometheus / OTel built in; Langfuse, custom Webhook etc. are in v1.x.
7. **Error body**: uniformly mapped to the OpenAI shape `{"error":{"message","type","code"}}`. `type` is the internal exception class name (e.g. `RateLimitException`).

## 5. Migration checklist

- [ ] All `model_list` fields used in `config.yaml` are covered by §1
- [ ] Required providers are in the 6 Tier-1 list, or are OpenAI-compatible endpoints
- [ ] No dependency on enterprise features (SSO / audit logs / enterprise Guardrails)
- [ ] Multi-replica deployments: either single-replica counter precision is acceptable, or you set `redis_url` for the Redis backend
- [ ] Clients only need to point `base_url` at the new gateway; auth still uses `Authorization: Bearer sk-...`

Missing something on the alignment list? Open an issue.
