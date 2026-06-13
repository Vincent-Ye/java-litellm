package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.proxy.keys.KeyService;
import dev.javalitellm.proxy.keys.VirtualKey;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Key management, master-key only (enforced by AuthFilter). Endpoint names mirror LiteLLM Proxy. */
@RestController
public class KeyController {

    private final KeyService keys;
    private final ObjectMapper mapper;

    public KeyController(KeyService keys, ObjectMapper mapper) {
        this.keys = keys;
        this.mapper = mapper;
    }

    @PostMapping(value = "/key/generate", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode generate(@RequestBody(required = false) JsonNode body) {
        JsonNode req = body == null ? mapper.createObjectNode() : body;
        List<String> models = new ArrayList<>();
        req.path("models").forEach(m -> models.add(m.asText()));
        BigDecimal maxBudget = req.hasNonNull("max_budget")
                ? BigDecimal.valueOf(req.get("max_budget").asDouble())
                : null;
        Duration validFor =
                req.hasNonNull("duration") ? parseDuration(req.get("duration").asText()) : null;

        String token = keys.generate(
                req.path("key_alias").asText(null),
                models,
                maxBudget,
                validFor,
                req.hasNonNull("tpm_limit") ? req.get("tpm_limit").asInt() : null,
                req.hasNonNull("rpm_limit") ? req.get("rpm_limit").asInt() : null,
                req.path("team_id").asText(null),
                req.path("user_id").asText(null));

        ObjectNode response = mapper.createObjectNode();
        response.put("key", token);
        response.put("token_hash", KeyService.hash(token));
        return response;
    }

    @PostMapping(value = "/key/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode info(
            @RequestParam(name = "key", required = false) String key, @RequestBody(required = false) JsonNode body) {
        String token =
                key != null ? key : body == null ? null : body.path("key").asText(null);
        VirtualKey vk = (token == null ? java.util.Optional.<VirtualKey>empty() : keys.find(token))
                .orElseThrow(() -> new NotFoundException("key not found", null, null));
        return toJson(vk);
    }

    @PostMapping(value = "/key/update", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode update(@RequestBody JsonNode body) {
        String token = body.path("key").asText(null);
        if (token == null) {
            throw new dev.javalitellm.core.exception.BadRequestException("'key' is required", null, null);
        }
        List<String> models = null;
        if (body.has("models")) {
            models = new ArrayList<>();
            for (JsonNode m : body.get("models")) {
                models.add(m.asText());
            }
        }
        boolean updated = keys.update(
                KeyService.hash(token),
                models,
                body.hasNonNull("max_budget")
                        ? BigDecimal.valueOf(body.get("max_budget").asDouble())
                        : null,
                body.hasNonNull("blocked") ? body.get("blocked").asBoolean() : null);
        if (!updated) {
            throw new NotFoundException("key not found", null, null);
        }
        return toJson(keys.find(token).orElseThrow());
    }

    @PostMapping(value = "/key/delete", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode delete(@RequestBody JsonNode body) {
        String token = body.path("key").asText(null);
        if (token == null || !keys.delete(KeyService.hash(token))) {
            throw new NotFoundException("key not found", null, null);
        }
        ObjectNode response = mapper.createObjectNode();
        response.put("deleted", true);
        return response;
    }

    private ObjectNode toJson(VirtualKey vk) {
        ObjectNode node = mapper.createObjectNode();
        node.put("token_hash", vk.tokenHash());
        node.put("key_alias", vk.alias());
        var models = node.putArray("models");
        vk.models().forEach(models::add);
        node.put("max_budget", vk.maxBudget() == null ? null : vk.maxBudget().doubleValue());
        node.put("spend", vk.spend() == null ? 0d : vk.spend().doubleValue());
        node.put("expires_at", vk.expiresAt() == null ? null : vk.expiresAt().toString());
        node.put("blocked", vk.blocked());
        node.put("tpm_limit", vk.tpmLimit());
        node.put("rpm_limit", vk.rpmLimit());
        node.put("team_id", vk.teamId());
        node.put("user_id", vk.userId());
        return node;
    }

    /** Durations in LiteLLM style: "30s", "30m", "30h", "30d". */
    private static Duration parseDuration(String text) {
        long value = Long.parseLong(text.substring(0, text.length() - 1));
        return switch (text.charAt(text.length() - 1)) {
            case 's' -> Duration.ofSeconds(value);
            case 'm' -> Duration.ofMinutes(value);
            case 'h' -> Duration.ofHours(value);
            case 'd' -> Duration.ofDays(value);
            default ->
                throw new dev.javalitellm.core.exception.BadRequestException(
                        "invalid duration '" + text + "', use e.g. 30s/30m/30h/30d", null, null);
        };
    }
}
