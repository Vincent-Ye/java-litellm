**English** | [中文](ROADMAP.zh.md)

# java-litellm — Development roadmap

> Companion to [DESIGN.md](DESIGN.md). Milestones are ordered by dependency; each has a clear set of verifiable deliverables.
> Estimates assume 1–2 full-time engineers; provider work can parallelize from M2 onward.

---

## Overview

```
M0 Scaffolding ─► M1 SDK core ─► M2 Providers ─► M3 Router ─► M4 Proxy MVP ─► M5 Gateway hardening ─► M6 v1.0 release
    (1 wk)         (3 wk)         (4 wk)         (3 wk)        (4 wk)            (4 wk)                  (2 wk)
```

About **21 weeks** (5 months) to v1.0. Each milestone ships a `0.x` preview to an internal repo; from M4 we publish Maven Central snapshots externally.

---

## M0 — Project scaffolding (week 1)

**Deliverables**
- [ ] Maven multi-module skeleton (core / client / provider-openai / router / cache / callbacks / proxy / bom)
- [ ] CI: GitHub Actions (build + test + spotless/checkstyle + JaCoCo coverage gate)
- [ ] Engineering conventions: Java 21, commit format, CONTRIBUTING.md

**Acceptance**: `mvn verify` green; empty-module dependency direction matches design §3.

---

## M1 — SDK core + first two providers (weeks 2–4)

**Scope**
- [ ] `litellm-core`: canonical OpenAI-format types (ChatRequest/Response, Message, polymorphic Content, Tool, Usage), exception hierarchy, model-string parsing (`provider/model`)
- [ ] `litellm-client`: `LiteLlm` facade, JDK HttpClient wrapper, timeouts and exponential-backoff retry, SSE parser
- [ ] `provider-openai`: chat (sync/async/streaming), tools, vision, embeddings; `apiBase` override (paves the way for OpenAI-compatible providers)
- [ ] `provider-anthropic`: full chat surface, tools, vision; handle system-prompt and required-`max_tokens` differences
- [ ] Cost calculation: bundled price-table snapshot + `completionCost()`; jtokkit token counting

**Acceptance**
- The same caller code switches between OpenAI and Anthropic with only the model string changed; streaming and tool calls work end to end.
- ≥ 30 transformer fixture golden cases; WireMock covers the 429/500/timeout retry paths.

**Risk**: rework around the polymorphic Content model → freeze the type design in the first week of M1, validate with fixtures before scaling out.

---

## M2 — Provider matrix + capability gaps (weeks 5–8, parallelizable)

**Scope**
- [ ] `provider-azure-openai` (api-version, deployment name mapping)
- [ ] `provider-gemini` (AI Studio + Vertex AI dual auth)
- [ ] `provider-bedrock` (SigV4 signing, Converse API)
- [ ] `provider-mistral`
- [ ] OpenAI-compatible validation: DeepSeek, Groq, Ollama, vLLM (docs + smoke tests, no separate modules)
- [ ] Capability gaps: align `/embeddings` across all providers, reasoning-model params (thinking / reasoning_effort), structured output (response_format / json_schema)
- [ ] Price table remote refresh + local overrides

**Acceptance**: all 6 Tier-1 providers pass a unified "capability matrix test suite" (one parameterized test set ran against every provider); the capability matrix doc is auto-generated.

---

## M3 — Router (weeks 9–11)

**Scope**
- [ ] Deployment / model-group model; both YAML and programmatic config
- [ ] Four routing strategies: simple-shuffle, least-busy, latency-based, usage-based
- [ ] Full retry → cooldown → fallback chain; fallback rules by exception type (incl. context-window fallback)
- [ ] `RouterStateStore`: in-process impl + Redis impl
- [ ] End-to-end total timeout budget

**Acceptance**: WireMock fault-injection tests — a single deployment's repeated 429s trigger cooldown; an exhausted group triggers cross-group fallback; deployments rejoin the pool after recovery. A 1000-concurrency virtual-thread stress test shows no state-contention issues.

---

## M4 — Proxy MVP (weeks 12–15)

**Scope**
- [ ] Spring Boot skeleton (virtual threads, graceful shutdown, three health probes)
- [ ] `POST /v1/chat/completions` (incl. SSE), `/v1/embeddings`, `GET /v1/models`
- [ ] `config.yaml` loader (compatible with LiteLLM's `model_list` syntax, `os.environ/` references)
- [ ] Virtual keys: `/key/generate|info|update|delete`, SHA-256 storage, model whitelist, expiry
- [ ] Postgres + Flyway schema; async `spend_logs` accounting
- [ ] Docker image + docker-compose (proxy + postgres + redis) one-command startup

**Acceptance**: pointing the **official OpenAI Java/Python SDK** at the Proxy passes chat/stream/embeddings contract tests; invalid keys yield 401, unauthorized models 403; recorded cost matches the SDK's `completionCost()`.

---

## M5 — Gateway hardening (weeks 16–19)

**Scope**
- [ ] Budgeting: three-level `max_budget` (Key / User / Team), rolling reset, reject on overrun
- [ ] Rate limiting: Redis sliding-window TPM/RPM, layered Key → Team → global check
- [ ] Cache: two-tier Caffeine + Redis exact cache (incl. streamed-response replay)
- [ ] Observability: full Prometheus metric set (latency quantiles / tokens / cost / error rate, tagged by model/key), OTel trace, structured logs
- [ ] Management API completeness: `/user/*`, `/team/*`, `/spend/*`, `/model/*` (dynamic model add/remove, hot-reload)
- [ ] Proxy-embedded Router (multi-deployment load balancing + fallback at the gateway layer)

**Acceptance**: under multi-replica Proxy + Redis, rate-limit / budget counters are accurate (concurrency stress error < 1%); sample Grafana dashboard; cache-hit-rate metrics visible.

---

## M6 — Hardening and v1.0 release (weeks 20–21)

**Scope**
- [ ] Performance benchmarking and tuning (target: gateway P99 self-overhead < 15ms, excluding upstream)
- [ ] Security review: key-leak paths, log redaction, dependency CVE scanning
- [ ] Docs site: getting started, migration guide ("Migrating from LiteLLM Proxy"), capability matrix, known differences from LiteLLM
- [ ] Maven Central GA release + Docker Hub images + GitHub Release

**Acceptance**: a new user can complete the SDK call + Proxy deployment + key issuance walkthrough end to end in 15 minutes by following the docs.

---

## v1.x roadmap (does not block v1.0)

| Topic | Content |
|------|------|
| Endpoint expansion | `/v1/images/*`, `/v1/audio/*`, `/v1/responses`, rerank, pass-through endpoints |
| Semantic cache | Embedding-similarity-based cache hits |
| Guardrails | Content-safety hook framework (pre/post-call guardrail SPI) + open-source guardrail adapters |
| Callback ecosystem | Langfuse, Webhook, S3/GCS log export |
| Admin UI | Management frontend (key / budget / usage dashboards) |
| Long-tail providers | Community SPI contribution workflow + provider certification test suite |

---

## Ongoing tasks (throughout all milestones)

- **Price table sync**: monthly refresh of the upstream `model_prices_and_context_window.json` snapshot.
- **Upstream tracking**: watch LiteLLM upstream for API semantic changes (management endpoints, config syntax); record deltas in the docs.
- **Per-milestone review**: deliverables that miss acceptance get explicitly downgraded or rescheduled — never carried forward unspoken.
