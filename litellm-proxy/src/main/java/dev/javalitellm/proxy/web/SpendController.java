package dev.javalitellm.proxy.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Spend reporting, master-key only (enforced by AuthFilter). */
@RestController
public class SpendController {

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public SpendController(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @GetMapping(value = "/spend/logs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArrayNode logs(@RequestParam(name = "limit", defaultValue = "100") int limit) {
        ArrayNode result = mapper.createArrayNode();
        jdbc.query(
                "SELECT token_hash, model_group, model, prompt_tokens, completion_tokens, cost_usd, created_at"
                        + " FROM spend_logs ORDER BY id DESC LIMIT ?",
                rs -> {
                    ObjectNode row = result.addObject();
                    row.put("token_hash", rs.getString("token_hash"));
                    row.put("model_group", rs.getString("model_group"));
                    row.put("model", rs.getString("model"));
                    row.put("prompt_tokens", rs.getObject("prompt_tokens", Integer.class));
                    row.put("completion_tokens", rs.getObject("completion_tokens", Integer.class));
                    row.put(
                            "cost_usd",
                            rs.getBigDecimal("cost_usd") == null
                                    ? null
                                    : rs.getBigDecimal("cost_usd").doubleValue());
                    row.put(
                            "created_at",
                            rs.getTimestamp("created_at").toInstant().toString());
                },
                Math.min(limit, 1000));
        return result;
    }

    @GetMapping(value = "/spend/keys", produces = MediaType.APPLICATION_JSON_VALUE)
    public ArrayNode byKey() {
        ArrayNode result = mapper.createArrayNode();
        jdbc.query("SELECT token_hash, key_alias, spend, max_budget FROM virtual_keys ORDER BY spend DESC", rs -> {
            ObjectNode row = result.addObject();
            row.put("token_hash", rs.getString("token_hash"));
            row.put("key_alias", rs.getString("key_alias"));
            row.put("spend", rs.getBigDecimal("spend").doubleValue());
            row.put(
                    "max_budget",
                    rs.getBigDecimal("max_budget") == null
                            ? null
                            : rs.getBigDecimal("max_budget").doubleValue());
        });
        return result;
    }
}
