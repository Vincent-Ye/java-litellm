package dev.javalitellm.provider.bedrock;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatChunk;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockDeltaEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStart;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlockStartEvent;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamMetadataEvent;
import software.amazon.awssdk.services.bedrockruntime.model.MessageStopEvent;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.TokenUsage;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockDelta;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlockStart;

class BedrockStreamParserTest {

    private final BedrockStreamParser parser = new BedrockStreamParser("model-x", new ObjectMapper());

    @Test
    void mapsTextDeltas() {
        ChatChunk chunk = parser.onContentBlockDelta(ContentBlockDeltaEvent.builder()
                .contentBlockIndex(0)
                .delta(ContentBlockDelta.fromText("Hel"))
                .build());

        assertThat(chunk.textDelta()).isEqualTo("Hel");
        assertThat(chunk.model()).isEqualTo("model-x");
    }

    @Test
    void stitchesToolUseAcrossStartAndDeltas() {
        ChatChunk start = parser.onContentBlockStart(ContentBlockStartEvent.builder()
                .contentBlockIndex(1)
                .start(ContentBlockStart.fromToolUse(ToolUseBlockStart.builder()
                        .toolUseId("toolu_1")
                        .name("get_weather")
                        .build()))
                .build());
        ChatChunk delta = parser.onContentBlockDelta(ContentBlockDeltaEvent.builder()
                .contentBlockIndex(1)
                .delta(ContentBlockDelta.fromToolUse(
                        ToolUseBlockDelta.builder().input("{\"city\":").build()))
                .build());

        assertThat(start.toolCallDeltas().getFirst().id()).isEqualTo("toolu_1");
        assertThat(start.toolCallDeltas().getFirst().name()).isEqualTo("get_weather");
        var d = delta.toolCallDeltas().getFirst();
        assertThat(d.index()).isEqualTo(1);
        assertThat(d.id()).isEqualTo("toolu_1");
        assertThat(d.argumentsDelta()).isEqualTo("{\"city\":");
    }

    @Test
    void mapsStopAndUsageEvents() {
        ChatChunk stop = parser.onMessageStop(
                MessageStopEvent.builder().stopReason(StopReason.END_TURN).build());
        ChatChunk usage = parser.onMetadata(ConverseStreamMetadataEvent.builder()
                .usage(TokenUsage.builder()
                        .inputTokens(7)
                        .outputTokens(3)
                        .totalTokens(10)
                        .build())
                .build());

        assertThat(stop.finishReason()).isEqualTo("stop");
        assertThat(usage.usage().promptTokens()).isEqualTo(7);
        assertThat(usage.usage().completionTokens()).isEqualTo(3);
    }

    @Test
    void ignoresNonToolBlockStarts() {
        assertThat(parser.onContentBlockStart(
                        ContentBlockStartEvent.builder().contentBlockIndex(0).build()))
                .isNull();
    }
}
