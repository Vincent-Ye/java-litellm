package dev.javalitellm.provider.openai;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatChunk;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.ResponseFormat;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolChoice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenAiTransformerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiTransformer transformer = new OpenAiTransformer(mapper);

    @Test
    void toWireMapsBasicFields() {
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.system("be brief"))
                .message(Message.user("hi"))
                .temperature(0.2)
                .maxTokens(64)
                .extraParam("logprobs", true)
                .build();

        ObjectNode wire = transformer.toWire(req, "gpt-4o", false);

        assertThat(wire.get("model").asText()).isEqualTo("gpt-4o");
        assertThat(wire.get("messages")).hasSize(2);
        assertThat(wire.get("messages").get(0).get("role").asText()).isEqualTo("system");
        assertThat(wire.get("messages").get(0).get("content").asText()).isEqualTo("be brief");
        assertThat(wire.get("temperature").asDouble()).isEqualTo(0.2);
        assertThat(wire.get("max_tokens").asInt()).isEqualTo(64);
        assertThat(wire.get("logprobs").asBoolean()).isTrue();
        assertThat(wire.has("stream")).isFalse();
    }

    @Test
    void toWireMapsMultimodalContentAsParts() {
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user(List.of(Content.text("describe"), Content.image("https://x/cat.png"))))
                .build();

        JsonNode content =
                transformer.toWire(req, "gpt-4o", false).get("messages").get(0).get("content");

        assertThat(content.isArray()).isTrue();
        assertThat(content.get(0).get("type").asText()).isEqualTo("text");
        assertThat(content.get(1).get("type").asText()).isEqualTo("image_url");
        assertThat(content.get(1).get("image_url").get("url").asText()).isEqualTo("https://x/cat.png");
    }

    @Test
    void toWireMapsToolsAndToolChoice() throws IOException {
        JsonNode params = mapper.readTree("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user("weather?"))
                .tools(List.of(new Tool("get_weather", "looks up weather", params)))
                .toolChoice(ToolChoice.function("get_weather"))
                .build();

        ObjectNode wire = transformer.toWire(req, "gpt-4o", false);

        JsonNode tool = wire.get("tools").get(0);
        assertThat(tool.get("type").asText()).isEqualTo("function");
        assertThat(tool.get("function").get("name").asText()).isEqualTo("get_weather");
        assertThat(tool.get("function").get("parameters")).isEqualTo(params);
        assertThat(wire.get("tool_choice").get("function").get("name").asText()).isEqualTo("get_weather");
    }

    @Test
    void toWireMapsJsonSchemaResponseFormat() throws IOException {
        JsonNode schema = mapper.readTree("{\"type\":\"object\"}");
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user("hi"))
                .responseFormat(ResponseFormat.jsonSchema("answer", schema))
                .build();

        JsonNode format = transformer.toWire(req, "gpt-4o", false).get("response_format");

        assertThat(format.get("type").asText()).isEqualTo("json_schema");
        assertThat(format.get("json_schema").get("name").asText()).isEqualTo("answer");
    }

    @Test
    void toWireStreamingEnablesUsage() {
        ChatRequest req = ChatRequest.builder()
                .model("gpt-4o")
                .message(Message.user("hi"))
                .build();

        ObjectNode wire = transformer.toWire(req, "gpt-4o", true);

        assertThat(wire.get("stream").asBoolean()).isTrue();
        assertThat(wire.get("stream_options").get("include_usage").asBoolean()).isTrue();
    }

    @Test
    void fromWireParsesBasicResponseFixture() {
        ChatResponse resp = transformer.fromWire(fixture("chat_response_basic.json"));

        assertThat(resp.id()).isEqualTo("chatcmpl-abc123");
        assertThat(resp.model()).isEqualTo("gpt-4o-2024-08-06");
        assertThat(resp.firstText()).isEqualTo("Hello! How can I help you today?");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(19);
        assertThat(resp.usage().completionTokens()).isEqualTo(10);
        assertThat(resp.usage().cachedTokens()).isZero();
    }

    @Test
    void fromWireParsesToolCallFixture() {
        ChatResponse resp = transformer.fromWire(fixture("chat_response_tool_calls.json"));

        assertThat(resp.firstText()).isEmpty();
        var call = resp.choices().getFirst().message().toolCalls().getFirst();
        assertThat(call.id()).isEqualTo("call_w1");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void chunkFromWireParsesTextDelta() throws IOException {
        JsonNode node = mapper.readTree(
                "{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},"
                        + "\"finish_reason\":null}]}");

        ChatChunk chunk = transformer.chunkFromWire(node);

        assertThat(chunk.textDelta()).isEqualTo("Hel");
        assertThat(chunk.finishReason()).isNull();
    }

    @Test
    void chunkFromWireParsesUsageOnlyChunk() throws IOException {
        JsonNode node =
                mapper.readTree("{\"id\":\"c1\",\"model\":\"gpt-4o\",\"choices\":[],\"usage\":{\"prompt_tokens\":5,"
                        + "\"completion_tokens\":7}}");

        ChatChunk chunk = transformer.chunkFromWire(node);

        assertThat(chunk.textDelta()).isEmpty();
        assertThat(chunk.usage().promptTokens()).isEqualTo(5);
        assertThat(chunk.usage().completionTokens()).isEqualTo(7);
    }

    private JsonNode fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
