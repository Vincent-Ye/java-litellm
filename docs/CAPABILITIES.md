**English** | [中文](CAPABILITIES.zh.md)

# Provider capability matrix

> Kept in sync with `ProviderDiscoveryTest` and each provider's `capabilities()` declaration. Update this table when adding a new provider.

| Capability | openai | anthropic | azure | mistral | gemini | bedrock |
|------|:------:|:---------:|:-----:|:-------:|:------:|:-------:|
| Chat (sync / async) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Streaming (SSE / event stream) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Tool calling (function calling) | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| Vision input (images) | ✅ | ✅ | ✅ | — | ✅ | ✅ |
| Embeddings | ✅ | — | ✅ | ✅ | ✅ | —¹ |
| Structured output (JSON Schema) | ✅ | — | ✅ | ✅ | ✅ | — |

¹ Bedrock embeddings go through per-family InvokeModel (Titan / Cohere); planned for v1.x.

## Configuration notes

| Provider | Example route prefix | Auth | Special configuration |
|----------|--------------|------|----------|
| openai | `openai/gpt-4o` (default when no prefix) | `Authorization: Bearer` | `apiBase` override reaches OpenAI-compatible endpoints (DeepSeek / Groq / Ollama / vLLM, etc.) |
| anthropic | `anthropic/claude-sonnet-4-6` | `x-api-key` | `max_tokens` defaults to 4096 when missing |
| azure | `azure/<deployment-name>` | `api-key` header | `apiBase` (resource endpoint) is **required**; `apiVersion` defaults to 2024-10-21 |
| mistral | `mistral/mistral-large-latest` | `Authorization: Bearer` | `stream_options` is stripped automatically |
| gemini | `gemini/gemini-2.0-flash` | `x-goog-api-key` | Function calls have no id; the function name doubles as `ToolCall.id` |
| bedrock | `bedrock/us.anthropic.claude-...` | AWS credential chain or `apiKey="ak:sk[:token]"` | `region` defaults to us-east-1; images must be supplied as base64 data URIs |
