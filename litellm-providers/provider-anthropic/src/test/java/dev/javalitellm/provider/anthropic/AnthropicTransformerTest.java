package dev.javalitellm.provider.anthropic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class AnthropicTransformerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicTransformer transformer = new AnthropicTransformer(mapper);

    @Test
    void hoistsSystemMessagesAndDefaultsMaxTokens() {
        ChatRequest req = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .message(Message.system("be brief"))
                .message(Message.user("hi"))
                .build();

        ObjectNode wire = transformer.toWire(req, "claude-sonnet-4-6", false);

        assertThat(wire.get("system").asText()).isEqualTo("be brief");
        assertThat(wire.get("max_tokens").asInt()).isEqualTo(AnthropicTransformer.DEFAULT_MAX_TOKENS);
        assertThat(wire.get("messages")).hasSize(1);
        assertThat(wire.get("messages").get(0).get("role").asText()).isEqualTo("user");
    }

    @Test
    void mapsToolsToInputSchema() throws IOException {
        JsonNode params = mapper.readTree("{\"type\":\"object\"}");
        ChatRequest req = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .message(Message.user("weather?"))
                .tools(List.of(new Tool("get_weather", "weather lookup", params)))
                .build();

        JsonNode tool =
                transformer.toWire(req, "claude-sonnet-4-6", false).get("tools").get(0);

        assertThat(tool.get("name").asText()).isEqualTo("get_weather");
        assertThat(tool.get("input_schema")).isEqualTo(params);
    }

    @Test
    void mapsAssistantToolCallsAndToolResults() {
        ChatRequest req = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .message(Message.user("weather?"))
                .message(Message.assistantToolCalls(
                        List.of(new ToolCall("toolu_1", "get_weather", "{\"city\":\"Tokyo\"}"))))
                .message(Message.toolResult("toolu_1", "22C sunny"))
                .build();

        JsonNode messages = transformer.toWire(req, "claude-sonnet-4-6", false).get("messages");

        JsonNode toolUse = messages.get(1).get("content").get(0);
        assertThat(toolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.get("input").get("city").asText()).isEqualTo("Tokyo");

        JsonNode toolResult = messages.get(2).get("content").get(0);
        assertThat(messages.get(2).get("role").asText()).isEqualTo("user");
        assertThat(toolResult.get("type").asText()).isEqualTo("tool_result");
        assertThat(toolResult.get("tool_use_id").asText()).isEqualTo("toolu_1");
        assertThat(toolResult.get("content").asText()).isEqualTo("22C sunny");
    }

    @Test
    void mapsBase64DataUriImages() {
        ChatRequest req = ChatRequest.builder()
                .model("claude-sonnet-4-6")
                .message(Message.user(List.of(Content.image("data:image/png;base64,iVBORw0K"))))
                .build();

        JsonNode source = transformer
                .toWire(req, "claude-sonnet-4-6", false)
                .get("messages")
                .get(0)
                .get("content")
                .get(0)
                .get("source");

        assertThat(source.get("type").asText()).isEqualTo("base64");
        assertThat(source.get("media_type").asText()).isEqualTo("image/png");
        assertThat(source.get("data").asText()).isEqualTo("iVBORw0K");
    }

    @Test
    void fromWireParsesBasicFixture() {
        ChatResponse resp = transformer.fromWire(fixture("messages_response_basic.json"));

        assertThat(resp.firstText()).isEqualTo("Hello! How can I assist you today?");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(12);
        assertThat(resp.usage().completionTokens()).isEqualTo(10);
        assertThat(resp.usage().cachedTokens()).isZero();
    }

    @Test
    void fromWireParsesToolUseFixture() {
        ChatResponse resp = transformer.fromWire(fixture("messages_response_tool_use.json"));

        assertThat(resp.firstText()).isEqualTo("I'll check the weather.");
        var call = resp.choices().getFirst().message().toolCalls().getFirst();
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("tool_calls");
    }

    private JsonNode fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
