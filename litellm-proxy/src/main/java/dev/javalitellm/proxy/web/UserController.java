package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.javalitellm.core.exception.BadRequestException;
import dev.javalitellm.core.exception.NotFoundException;
import dev.javalitellm.proxy.users.UserService;
import java.math.BigDecimal;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** User management, master-key only (enforced by AuthFilter via /user/ prefix). */
@RestController
public class UserController {

    private final UserService users;
    private final ObjectMapper mapper;

    public UserController(UserService users, ObjectMapper mapper) {
        this.users = users;
        this.mapper = mapper;
    }

    @PostMapping(value = "/user/new", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode create(@RequestBody(required = false) JsonNode body) {
        JsonNode req = body == null ? mapper.createObjectNode() : body;
        String userId = users.create(
                req.path("user_alias").asText(null),
                req.hasNonNull("max_budget")
                        ? BigDecimal.valueOf(req.get("max_budget").asDouble())
                        : null);
        return users.info(userId).orElseThrow();
    }

    @PostMapping(value = "/user/info", produces = MediaType.APPLICATION_JSON_VALUE)
    public ObjectNode info(@RequestBody JsonNode body) {
        String userId = body.path("user_id").asText(null);
        if (userId == null) {
            throw new BadRequestException("'user_id' is required", null, null);
        }
        return users.info(userId).orElseThrow(() -> new NotFoundException("user not found", null, null));
    }
}
