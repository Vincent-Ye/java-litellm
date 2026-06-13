package dev.javalitellm.cache;

import dev.javalitellm.core.chat.ChatRequest;
import dev.javalitellm.core.chat.Content;
import dev.javalitellm.core.chat.Message;
import dev.javalitellm.core.chat.Tool;
import dev.javalitellm.core.chat.ToolCall;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;

/**
 * Deterministic cache key: SHA-256 over a canonical rendering of every request field that affects
 * the completion. Two requests that differ only in non-semantic ways (field order in extraParams)
 * hash identically.
 */
public final class ChatCacheKey {

    private ChatCacheKey() {}

    public static String of(ChatRequest request) {
        StringBuilder sb = new StringBuilder();
        sb.append("model=").append(request.model()).append('\n');
        for (Message message : request.messages()) {
            sb.append("msg:").append(message.role()).append(':');
            for (Content part : message.content()) {
                switch (part) {
                    case Content.Text(String text) ->
                        sb.append("t[").append(text).append(']');
                    case Content.Image(String url, String detail) ->
                        sb.append("i[").append(url).append(',').append(detail).append(']');
                    case Content.Audio(String data, String format) ->
                        sb.append("a[").append(format).append(',').append(data).append(']');
                }
            }
            if (message.toolCalls() != null) {
                for (ToolCall call : message.toolCalls()) {
                    sb.append("call[")
                            .append(call.id())
                            .append(',')
                            .append(call.name())
                            .append(',')
                            .append(call.arguments())
                            .append(']');
                }
            }
            if (message.toolCallId() != null) {
                sb.append("for[").append(message.toolCallId()).append(']');
            }
            sb.append('\n');
        }
        sb.append("temp=").append(request.temperature()).append('\n');
        sb.append("topP=").append(request.topP()).append('\n');
        sb.append("maxTokens=").append(request.maxTokens()).append('\n');
        sb.append("stop=").append(request.stop()).append('\n');
        if (request.tools() != null) {
            for (Tool tool : request.tools()) {
                sb.append("tool[")
                        .append(tool.name())
                        .append(',')
                        .append(tool.parameters())
                        .append(']');
            }
            sb.append('\n');
        }
        sb.append("toolChoice=").append(request.toolChoice()).append('\n');
        if (request.responseFormat() != null) {
            sb.append("format=")
                    .append(request.responseFormat().type())
                    .append(',')
                    .append(request.responseFormat().jsonSchema())
                    .append('\n');
        }
        for (Map.Entry<String, Object> extra : new TreeMap<>(request.extraParams()).entrySet()) {
            sb.append("x:")
                    .append(extra.getKey())
                    .append('=')
                    .append(extra.getValue())
                    .append('\n');
        }
        return sha256(sb.toString());
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
