package dev.javalitellm.provider.bedrock;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.core.chat.Choice;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Role;
import dev.javalitellm.core.chat.ToolCall;
import dev.javalitellm.core.chat.Usage;
import dev.javalitellm.core.exception.BadRequestException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.bedrockruntime.model.ContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ConversationRole;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseRequest;
import software.amazon.awssdk.services.bedrockruntime.model.ConverseResponse;
import software.amazon.awssdk.services.bedrockruntime.model.ImageBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ImageSource;
import software.amazon.awssdk.services.bedrockruntime.model.InferenceConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.SpecificToolChoice;
import software.amazon.awssdk.services.bedrockruntime.model.StopReason;
import software.amazon.awssdk.services.bedrockruntime.model.SystemContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolConfiguration;
import software.amazon.awssdk.services.bedrockruntime.model.ToolInputSchema;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolResultContentBlock;
import software.amazon.awssdk.services.bedrockruntime.model.ToolSpecification;
import software.amazon.awssdk.services.bedrockruntime.model.ToolUseBlock;

/**
 * Mapping between canonical (OpenAI-format) types and the Bedrock Converse API. The AWS SDK owns
 * the wire format, SigV4 signing and event-stream framing; this class only translates object
 * models. SDK type names clash with canonical ones (Message, Tool, ToolChoice), hence the
 * fully-qualified references.
 */
final class BedrockTransformer {

    private static final String PROVIDER = "bedrock";

    private final ObjectMapper mapper;

    BedrockTransformer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ConverseRequest toConverseRequest(ChatRequest req, String modelId) {
        ConverseRequest.Builder builder = ConverseRequest.builder().modelId(modelId);

        List<SystemContentBlock> system = new ArrayList<>();
        List<software.amazon.awssdk.services.bedrockruntime.model.Message> messages = new ArrayList<>();
        for (Message msg : req.messages()) {
            if (msg.role() == Role.SYSTEM) {
                system.add(SystemContentBlock.fromText(msg.text()));
            } else {
                messages.add(toConverseMessage(msg, modelId));
            }
        }
        if (!system.isEmpty()) {
            builder.system(system);
        }
        builder.messages(messages);

        InferenceConfiguration.Builder inference = InferenceConfiguration.builder();
        boolean hasInference = false;
        if (req.maxTokens() != null) {
            inference.maxTokens(req.maxTokens());
            hasInference = true;
        }
        if (req.temperature() != null) {
            inference.temperature(req.temperature().floatValue());
            hasInference = true;
        }
        if (req.topP() != null) {
            inference.topP(req.topP().floatValue());
            hasInference = true;
        }
        if (req.stop() != null) {
            inference.stopSequences(req.stop());
            hasInference = true;
        }
        if (hasInference) {
            builder.inferenceConfig(inference.build());
        }

        if (req.tools() != null) {
            List<software.amazon.awssdk.services.bedrockruntime.model.Tool> tools = new ArrayList<>();
            for (dev.javalitellm.core.chat.Tool tool : req.tools()) {
                tools.add(software.amazon.awssdk.services.bedrockruntime.model.Tool.fromToolSpec(
                        ToolSpecification.builder()
                                .name(tool.name())
                                .description(tool.description())
                                .inputSchema(ToolInputSchema.fromJson(DocumentJson.fromJson(tool.parameters())))
                                .build()));
            }
            ToolConfiguration.Builder toolConfig = ToolConfiguration.builder().tools(tools);
            if (req.toolChoice() != null) {
                switch (req.toolChoice().mode()) {
                    case AUTO ->
                        toolConfig.toolChoice(
                                software.amazon.awssdk.services.bedrockruntime.model.ToolChoice.fromAuto(b -> {}));
                    case REQUIRED ->
                        toolConfig.toolChoice(
                                software.amazon.awssdk.services.bedrockruntime.model.ToolChoice.fromAny(b -> {}));
                    case FUNCTION ->
                        toolConfig.toolChoice(software.amazon.awssdk.services.bedrockruntime.model.ToolChoice.fromTool(
                                SpecificToolChoice.builder()
                                        .name(req.toolChoice().functionName())
                                        .build()));
                    case NONE -> {
                        // Converse has no NONE mode; omitting toolConfig entirely would be closest,
                        // but tools were supplied — leave choice to the model (AUTO).
                    }
                }
            }
            builder.toolConfig(toolConfig.build());
        }
        return builder.build();
    }

    /** Same mapping for streaming; ConverseStreamRequest mirrors ConverseRequest field-for-field. */
    software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest toConverseStreamRequest(
            ChatRequest req, String modelId) {
        ConverseRequest converse = toConverseRequest(req, modelId);
        return software.amazon.awssdk.services.bedrockruntime.model.ConverseStreamRequest.builder()
                .modelId(converse.modelId())
                .messages(converse.messages())
                .system(converse.system())
                .inferenceConfig(converse.inferenceConfig())
                .toolConfig(converse.toolConfig())
                .build();
    }

    private software.amazon.awssdk.services.bedrockruntime.model.Message toConverseMessage(Message msg, String model) {
        List<ContentBlock> blocks = new ArrayList<>();

        if (msg.role() == Role.TOOL) {
            // Tool results travel as a toolResult block inside a user message.
            return software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                    .role(ConversationRole.USER)
                    .content(ContentBlock.fromToolResult(ToolResultBlock.builder()
                            .toolUseId(msg.toolCallId())
                            .content(ToolResultContentBlock.fromText(msg.text()))
                            .build()))
                    .build();
        }

        for (Content part : msg.content()) {
            switch (part) {
                case Content.Text(String text) -> blocks.add(ContentBlock.fromText(text));
                case Content.Image(String url, String ignored) -> blocks.add(toImageBlock(url, model));
                case Content.Audio ignored ->
                    throw new BadRequestException("bedrock converse does not accept audio content", PROVIDER, model);
            }
        }
        if (msg.toolCalls() != null) {
            for (ToolCall call : msg.toolCalls()) {
                blocks.add(ContentBlock.fromToolUse(ToolUseBlock.builder()
                        .toolUseId(call.id())
                        .name(call.name())
                        .input(DocumentJson.fromJson(parseJson(call.arguments(), model)))
                        .build()));
            }
        }
        return software.amazon.awssdk.services.bedrockruntime.model.Message.builder()
                .role(msg.role() == Role.ASSISTANT ? ConversationRole.ASSISTANT : ConversationRole.USER)
                .content(blocks)
                .build();
    }

    private ContentBlock toImageBlock(String url, String model) {
        if (!url.startsWith("data:")) {
            throw new BadRequestException(
                    "bedrock requires images as base64 data URIs, not remote URLs", PROVIDER, model);
        }
        int semi = url.indexOf(';');
        int comma = url.indexOf(',');
        if (semi < 0 || comma < 0) {
            throw new BadRequestException("malformed data URI in image content", PROVIDER, model);
        }
        String mediaType = url.substring("data:".length(), semi); // e.g. image/png
        String format = mediaType.substring(mediaType.indexOf('/') + 1);
        byte[] bytes = Base64.getDecoder().decode(url.substring(comma + 1));
        return ContentBlock.fromImage(ImageBlock.builder()
                .format(format)
                .source(ImageSource.fromBytes(SdkBytes.fromByteArray(bytes)))
                .build());
    }

    ChatResponse fromConverseResponse(ConverseResponse response, String modelId) {
        List<Content> content = new ArrayList<>();
        List<ToolCall> toolCalls = new ArrayList<>();
        if (response.output() != null && response.output().message() != null) {
            for (ContentBlock block : response.output().message().content()) {
                if (block.text() != null) {
                    content.add(Content.text(block.text()));
                } else if (block.toolUse() != null) {
                    ToolUseBlock use = block.toolUse();
                    toolCalls.add(new ToolCall(
                            use.toolUseId(),
                            use.name(),
                            DocumentJson.toJson(use.input(), mapper).toString()));
                }
            }
        }
        Message message = new Message(Role.ASSISTANT, content, toolCalls.isEmpty() ? null : toolCalls, null);

        Usage usage = response.usage() == null
                ? null
                : new Usage(
                        response.usage().inputTokens(),
                        response.usage().outputTokens(),
                        response.usage().cacheReadInputTokens(),
                        null);
        return new ChatResponse(
                null, modelId, 0, List.of(new Choice(0, message, mapStopReason(response.stopReason()))), usage, null);
    }

    static String mapStopReason(StopReason stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case END_TURN, STOP_SEQUENCE -> "stop";
            case MAX_TOKENS -> "length";
            case TOOL_USE -> "tool_calls";
            case GUARDRAIL_INTERVENED, CONTENT_FILTERED -> "content_filter";
            default -> stopReason.toString().toLowerCase();
        };
    }

    private com.fasterxml.jackson.databind.JsonNode parseJson(String json, String model) {
        try {
            return mapper.readTree(json == null || json.isBlank() ? "{}" : json);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("tool call arguments are not valid JSON: " + json, PROVIDER, model, e);
        }
    }
}
