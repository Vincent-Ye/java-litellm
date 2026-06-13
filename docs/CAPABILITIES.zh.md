[English](CAPABILITIES.md) | **中文**

# 供应商能力矩阵

> 与 `ProviderDiscoveryTest` 及各 provider 的 `capabilities()` 声明保持一致。新增 provider 时同步更新本表。

| 能力 | openai | anthropic | azure | mistral | gemini | bedrock |
|------|:------:|:---------:|:-----:|:-------:|:------:|:-------:|
| Chat（同步/异步） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 流式（SSE / event stream） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 工具调用（function calling） | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 视觉输入（图片） | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| Embeddings | ✅ | — | ✅ | ✅ | ✅ | —¹ |
| 结构化输出（JSON Schema） | ✅ | — | ✅ | ✅ | ✅ | — |

¹ Bedrock 的 embedding 走按模型族的 InvokeModel（Titan/Cohere），列入 v1.x。

## 配置要点

| Provider | 路由前缀示例 | 认证 | 特殊配置 |
|----------|--------------|------|----------|
| openai | `openai/gpt-4o`（无前缀默认） | `Authorization: Bearer` | `apiBase` 覆盖可接 DeepSeek/Groq/Ollama/vLLM 等兼容端点 |
| anthropic | `anthropic/claude-sonnet-4-6` | `x-api-key` | `max_tokens` 缺省自动补 4096 |
| azure | `azure/<deployment-name>` | `api-key` 头 | **必须**设 `apiBase`（资源端点）；`apiVersion` 默认 2024-10-21 |
| mistral | `mistral/mistral-large-latest` | `Authorization: Bearer` | 自动裁剪 `stream_options` |
| gemini | `gemini/gemini-2.0-flash` | `x-goog-api-key` | 函数调用无 id，函数名兼作 ToolCall.id |
| bedrock | `bedrock/us.anthropic.claude-...` | AWS 凭证链或 `apiKey="ak:sk[:token]"` | `region` 默认 us-east-1；图片仅支持 base64 data URI |
