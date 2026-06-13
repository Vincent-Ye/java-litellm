package dev.javalitellm.provider.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.ToolChoice;
import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

class BedrockTransformerTest {

    private static final String MODEL = "us.anthropic.claude-sonnet-4-6";

    private final ObjectMapper mapper = new ObjectMapper();
    private final BedrockTransformer transformer = new BedrockTransformer(mapper);

    @Test
    void hoistsSystemAndMapsInferenceConfig() {
        ChatRequest req = ChatRequest.builder()
                .model(MODEL)
                .message(Message.system("be brief"))
                .message(Message.user("hi"))
                .temperature(0.4)
                .maxTokens(256)
                .build();

        ConverseRequest converse = transformer.toConverseRequest(req, MODEL);

        assertThat(converse.modelId()).isEqualTo(MODEL);
        assertThat(converse.system().getFirst().text()).isEqualTo("be brief");
        assertThat(converse.messages()).hasSize(1);
        assertThat(converse.messages().getFirst().role()).isEqualTo(ConversationRole.USER);
        assertThat(converse.inferenceConfig().temperature()).isEqualTo(0.4f);
        assertThat(converse.inferenceConfig().maxTokens()).isEqualTo(256);
    }

    @Test
    void mapsToolsToolChoiceAndToolResults() throws IOException {
        JsonNode params = mapper.readTree("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}");
        ChatRequest req = ChatRequest.builder()
                .model(MODEL)
                .message(Message.user("weather?"))
                .message(Message.assistantToolCalls(
                        List.of(new ToolCall("toolu_1", "get_weather", "{\"city\":\"Tokyo\"}"))))
                .message(Message.toolResult("toolu_1", "22C sunny"))
                .tools(List.of(new Tool("get_weather", "weather lookup", params)))
                .toolChoice(ToolChoice.function("get_weather"))
                .build();

        ConverseRequest converse = transformer.toConverseRequest(req, MODEL);

        var spec = converse.toolConfig().tools().getFirst().toolSpec();
        assertThat(spec.name()).isEqualTo("get_weather");
        assertThat(spec.inputSchema().json().asMap().get("type").asString()).isEqualTo("object");
        assertThat(converse.toolConfig().toolChoice().tool().name()).isEqualTo("get_weather");

        var toolUse = converse.messages().get(1).content().getFirst().toolUse();
        assertThat(toolUse.toolUseId()).isEqualTo("toolu_1");
        assertThat(toolUse.input().asMap().get("city").asString()).isEqualTo("Tokyo");

        var toolResult = converse.messages().get(2).content().getFirst().toolResult();
        assertThat(converse.messages().get(2).role()).isEqualTo(ConversationRole.USER);
        assertThat(toolResult.toolUseId()).isEqualTo("toolu_1");
        assertThat(toolResult.content().getFirst().text()).isEqualTo("22C sunny");
    }

    @Test
    void streamRequestMirrorsConverseRequest() {
        ChatRequest req = ChatRequest.builder()
                .model(MODEL)
                .message(Message.system("be brief"))
                .message(Message.user("hi"))
                .maxTokens(64)
                .build();

        ConverseStreamRequest stream = transformer.toConverseStreamRequest(req, MODEL);

        assertThat(stream.modelId()).isEqualTo(MODEL);
        assertThat(stream.system()).hasSize(1);
        assertThat(stream.messages()).hasSize(1);
        assertThat(stream.inferenceConfig().maxTokens()).isEqualTo(64);
    }

    @Test
    void fromConverseResponseMapsTextToolUseAndUsage() {
        ConverseResponse response = ConverseResponse.builder()
                .output(o -> o.message(software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                        .role(ConversationRole.ASSISTANT)
                        .content(
                                ContentBlock.fromText("checking"),
                                ContentBlock.fromToolUse(ToolUseBlock.builder()
                                        .toolUseId("toolu_9")
                                        .name("get_weather")
                                        .input(DocumentJson.fromJson(json("{\"city\":\"Tokyo\"}")))
                                        .build()))
                        .build()))
                .stopReason(StopReason.TOOL_USE)
                .usage(TokenUsage.builder()
                        .inputTokens(40)
                        .outputTokens(12)
                        .totalTokens(52)
                        .build())
                .build();

        ChatResponse resp = transformer.fromConverseResponse(response, MODEL);

        assertThat(resp.firstText()).isEqualTo("checking");
        var call = resp.choices().getFirst().message().toolCalls().getFirst();
        assertThat(call.id()).isEqualTo("toolu_9");
        assertThat(json(call.arguments()).get("city").asText()).isEqualTo("Tokyo");
        assertThat(resp.choices().getFirst().finishReason()).isEqualTo("tool_calls");
        assertThat(resp.usage().promptTokens()).isEqualTo(40);
        assertThat(resp.usage().completionTokens()).isEqualTo(12);
    }

    @Test
    void mapsStopReasons() {
        assertThat(BedrockTransformer.mapStopReason(StopReason.END_TURN)).isEqualTo("stop");
        assertThat(BedrockTransformer.mapStopReason(StopReason.MAX_TOKENS)).isEqualTo("length");
        assertThat(BedrockTransformer.mapStopReason(StopReason.TOOL_USE)).isEqualTo("tool_calls");
        assertThat(BedrockTransformer.mapStopReason(StopReason.GUARDRAIL_INTERVENED))
                .isEqualTo("content_filter");
    }

    private JsonNode json(String text) {
        try {
            return mapper.readTree(text);
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
