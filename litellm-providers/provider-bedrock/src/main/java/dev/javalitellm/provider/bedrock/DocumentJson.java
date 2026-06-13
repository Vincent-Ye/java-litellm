package dev.javalitellm.provider.bedrock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import software.amazon.awssdk.core.document.Document;

/** Bidirectional conversion between Jackson trees and the AWS SDK's {@link Document} JSON model. */
final class DocumentJson {

    private DocumentJson() {}

    static Document fromJson(JsonNode node) {
        return switch (node.getNodeType()) {
            case OBJECT -> {
                Document.MapBuilder map = Document.mapBuilder();
                node.properties().forEach(entry -> map.putDocument(entry.getKey(), fromJson(entry.getValue())));
                yield map.build();
            }
            case ARRAY -> {
                Document.ListBuilder list = Document.listBuilder();
                node.forEach(item -> list.addDocument(fromJson(item)));
                yield list.build();
            }
            case STRING -> Document.fromString(node.asText());
            case BOOLEAN -> Document.fromBoolean(node.asBoolean());
            case NUMBER ->
                node.isIntegralNumber() ? Document.fromNumber(node.asLong()) : Document.fromNumber(node.asDouble());
            default -> Document.fromNull();
        };
    }

    static JsonNode toJson(Document document, ObjectMapper mapper) {
        if (document == null || document.isNull()) {
            return mapper.nullNode();
        }
        if (document.isMap()) {
            ObjectNode node = mapper.createObjectNode();
            for (Map.Entry<String, Document> entry : document.asMap().entrySet()) {
                node.set(entry.getKey(), toJson(entry.getValue(), mapper));
            }
            return node;
        }
        if (document.isList()) {
            ArrayNode node = mapper.createArrayNode();
            document.asList().forEach(item -> node.add(toJson(item, mapper)));
            return node;
        }
        if (document.isString()) {
            return mapper.getNodeFactory().textNode(document.asString());
        }
        if (document.isBoolean()) {
            return mapper.getNodeFactory().booleanNode(document.asBoolean());
        }
        // number
        return mapper.getNodeFactory().numberNode(document.asNumber().doubleValue());
    }
}
