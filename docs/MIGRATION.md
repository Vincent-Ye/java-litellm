# 从 LiteLLM (Python) 迁移

java-litellm 在**配置文件**和**管理 API** 层面刻意对齐 LiteLLM，多数场景可平滑迁移。本文列出对齐点与已知差异。

---

## 1. config.yaml

`model_list` / `litellm_params` / `general_settings` / `litellm_settings` 的核心结构一致，`os.environ/VAR` 引用语法相同：

```yaml
model_list:
  - model_name: gpt-4o
    litellm_params:
      model: openai/gpt-4o
      api_key: os.environ/OPENAI_API_KEY
      api_base: https://...        # 可选；OpenAI 兼容端点用它覆盖
      api_version: 2024-10-21      # Azure
      aws_region_name: us-east-1   # Bedrock
      weight: 2                    # 负载均衡权重
      tpm: 100000                  # 部署级 TPM/RPM（usage-based 路由用）
      rpm: 600
litellm_settings:
  cache: true
  cache_params:
    ttl: 300
general_settings:
  master_key: os.environ/LITELLM_MASTER_KEY
```

> 已支持的字段子集即上表。LiteLLM 中更冷门的 `litellm_params`（如部分 guardrail、router 调参）尚未实现，迁移时会被忽略而非报错——请对照本文确认关键字段都在支持范围内。

## 2. 端点对齐

| 端点 | 状态 |
|------|------|
| `POST /v1/chat/completions`（含 SSE） | ✅ |
| `POST /v1/embeddings` | ✅ |
| `GET /v1/models` | ✅（按 Key 权限过滤） |
| `POST /key/generate\|info\|update\|delete` | ✅ |
| `POST /team/new\|info\|update\|delete` | ✅ |
| `POST /user/new\|info` | ✅ |
| `POST /model/new\|delete`、`GET /model/info` | ✅（热生效） |
| `GET /spend/logs\|keys` | ✅ |
| `GET /health`、`/actuator/health`、`/actuator/prometheus` | ✅ |
| `POST /v1/completions`、`/v1/images/*`、`/v1/audio/*`、rerank、pass-through | ⏳ v1.x |

虚拟 Key 行为一致：`sk-` 前缀、库中只存 SHA-256 哈希、模型白名单、`max_budget`、`duration`（`30s/30m/30h/30d`）、TPM/RPM 限额、过期与封禁。

## 3. 供应商前缀

模型路由字符串约定与 LiteLLM 相同：`openai/...`、`anthropic/...`、`azure/...`、`gemini/...`、`bedrock/...`、`mistral/...`，无前缀按 OpenAI 处理。OpenAI 兼容供应商（DeepSeek、Groq、Together、Ollama、vLLM 等）用 `provider-openai` + `api_base` 覆盖即可，与 LiteLLM 思路一致。

## 4. 已知差异

1. **供应商范围**：v1.0 聚焦 6 家 Tier-1 供应商（见 [CAPABILITIES.md](CAPABILITIES.md)），不是 LiteLLM 的 100+。其余通过 SPI 由社区扩展。
2. **企业版功能不在范围内**：SSO/SAML、审计日志、JWT 团队鉴权、企业级 Guardrails 不实现（这些在 LiteLLM 中也非纯开源部分）。
3. **分布式后端**：默认进程内实现；在 `config.yaml` 的 `litellm_settings.redis_url` 或 `general_settings.redis_url` 设值后，缓存（Redis SETEX）、限流（Lua 原子滑动窗口）、Router 冷却与时延窗口都切到 Redis，多副本部署计数精确（误差由 Redis 一致性决定，非进程独立）。
4. **Token 计数**：OpenAI 系用 jtokkit 精确计数；非 OpenAI 系优先用响应 `usage` 字段，预估场景退化为近似——与 LiteLLM `token_counter` 行为一致。
5. **价格表**：复用 LiteLLM 的 `model_prices_and_context_window.json`，内置快照打进 jar；未知模型的成本返回 `null` 而非 0，便于区分「免费」与「未知」。
6. **回调生态**：v1.0 内置日志/Prometheus/OTel；Langfuse、自定义 Webhook 等在 v1.x。
7. **错误体**：统一映射为 OpenAI 风格 `{"error":{"message","type","code"}}`，`type` 为内部异常类名（如 `RateLimitException`）。

## 5. 迁移检查清单

- [ ] `config.yaml` 的 `model_list` 字段都在 §1 支持范围内
- [ ] 用到的供应商在 6 家 Tier-1 之内，或为 OpenAI 兼容端点
- [ ] 没有依赖企业版功能（SSO/审计日志/企业 Guardrail）
- [ ] 多副本部署的限流/预算精度要求可接受单副本计数，或等待 Redis 后端
- [ ] 客户端只需把 `base_url` 指向新网关，鉴权仍用 `Authorization: Bearer sk-...`

有遗漏的对齐需求，欢迎提 issue。
