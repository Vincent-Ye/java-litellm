package dev.javalitellm.proxy.users;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** End users for spend attribution; lighter than teams (no rate limits today). */
@Service
public class UserService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public UserService(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    public String create(String alias, BigDecimal maxBudget) {
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        String userId = "user-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        jdbc.update("INSERT INTO users (user_id, user_alias, max_budget) VALUES (?,?,?)", userId, alias, maxBudget);
        return userId;
    }

    public Optional<ObjectNode> info(String userId) {
        List<ObjectNode> rows = jdbc.query(
                "SELECT user_id, user_alias, max_budget, spend FROM users WHERE user_id = ?",
                (rs, i) -> {
                    ObjectNode node = mapper.createObjectNode();
                    node.put("user_id", rs.getString("user_id"));
                    node.put("user_alias", rs.getString("user_alias"));
                    node.put(
                            "max_budget",
                            rs.getBigDecimal("max_budget") == null
                                    ? null
                                    : rs.getBigDecimal("max_budget").doubleValue());
                    node.put("spend", rs.getBigDecimal("spend").doubleValue());
                    return node;
                },
                userId);
        return rows.stream().findFirst();
    }

    public void addSpend(String userId, BigDecimal amount) {
        jdbc.update("UPDATE users SET spend = spend + ? WHERE user_id = ?", amount, userId);
    }
}
