package dev.javalitellm.provider.gemini;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.ToolChoice;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import org.junit.jupiter.api.Test;

class GeminiTransformerTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final GeminiTransformer transformer = new GeminiTransformer(mapper);

    @Test
    void mapsRolesSystemInstructionAndGenerationConfig() {
        ChatRequest req = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .message(Message.system("be brief"))
                .message(Message.user("hi"))
                .message(Message.assistant("hello"))
                .temperature(0.3)
                .maxTokens(128)
                .build();

        ObjectNode wire = transformer.toWire(req, "gemini-2.0-flash");

        assertThat(wire.get("systemInstruction").get("parts").get(0).get("text").asText())
                .isEqualTo("be brief");
        assertThat(wire.get("contents")).hasSize(2);
        assertThat(wire.get("contents").get(0).get("role").asText()).isEqualTo("user");
        assertThat(wire.get("contents").get(1).get("role").asText()).isEqualTo("model");
        assertThat(wire.get("generationConfig").get("temperature").asDouble()).isEqualTo(0.3);
        assertThat(wire.get("generationConfig").get("maxOutputTokens").asInt()).isEqualTo(128);
    }

    @Test
    void mapsToolsAndForcedToolChoice() throws IOException {
        JsonNode params = mapper.readTree("{\"type\":\"object\"}");
        ChatRequest req = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .message(Message.user("weather?"))
                .tools(List.of(new Tool("get_weather", "weather lookup", params)))
                .toolChoice(ToolChoice.function("get_weather"))
                .build();

        ObjectNode wire = transformer.toWire(req, "gemini-2.0-flash");

        JsonNode declaration =
                wire.get("tools").get(0).get("functionDeclarations").get(0);
        assertThat(declaration.get("name").asText()).isEqualTo("get_weather");
        JsonNode fnConfig = wire.get("toolConfig").get("functionCallingConfig");
        assertThat(fnConfig.get("mode").asText()).isEqualTo("ANY");
        assertThat(fnConfig.get("allowedFunctionNames").get(0).asText()).isEqualTo("get_weather");
    }

    @Test
    void mapsToolCallAndToolResultRoundTrip() {
        ChatRequest req = ChatRequest.builder()
                .model("gemini-2.0-flash")
                .message(Message.user("weather?"))
                .message(Message.assistantToolCalls(
                        List.of(new ToolCall("get_weather", "get_weather", "{\"city\":\"Tokyo\"}"))))
                .message(Message.toolResult("get_weather", "22C sunny"))
                .build();

        JsonNode contents = transformer.toWire(req, "gemini-2.0-flash").get("contents");

        JsonNode fnCall = contents.get(1).get("parts").get(0).get("functionCall");
        assertThat(fnCall.get("name").asText()).isEqualTo("get_weather");
        assertThat(fnCall.get("args").get("city").asText()).isEqualTo("Tokyo");

        JsonNode fnResponse = contents.get(2).get("parts").get(0).get("functionResponse");
        assertThat(contents.get(2).get("role").asText()).isEqualTo("user");
        assertThat(fnResponse.get("name").asText()).isEqualTo("get_weather");
        assertThat(fnResponse.get("response").get("content").asText()).isEqualTo("22C sunny");
    }

    @Test
    void fromWireParsesBasicFixture() {
        ChatResponse resp = transformer.fromWire(fixture("generate_content_basic.json"));

        assertThat(resp.firstText()).isEqualTo("Hello there! How can I help you?");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(resp.usage().promptTokens()).isEqualTo(8);
        assertThat(resp.usage().completionTokens()).isEqualTo(9);
        assertThat(resp.model()).isEqualTo("gemini-2.0-flash");
    }

    @Test
    void fromWireParsesFunctionCallFixtureWithNameAsId() {
        ChatResponse resp = transformer.fromWire(fixture("generate_content_function_call.json"));

        var call = resp.choices().getFirst().message().toolCalls().getFirst();
        assertThat(call.id()).isEqualTo("get_weather");
        assertThat(call.name()).isEqualTo("get_weather");
        assertThat(call.arguments()).isEqualTo("{\"city\":\"Tokyo\"}");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("tool_calls");
    }

    @Test
    void mapsSafetyFinishToContentFilter() throws IOException {
        JsonNode root = mapper.readTree(
                "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"\"}]},\"finishReason\":\"SAFETY\"}]}");

        ChatResponse resp = transformer.fromWire(root);

        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("content_filter");
    }

    private JsonNode fixture(String name) {
        try (var in = getClass().getResourceAsStream("/fixtures/" + name)) {
            return mapper.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
