package dev.javalitellm.proxy.spend;

import dev.javalitellm.core.chat.ChatResponse;
import dev.javalitellm.proxy.keys.KeyService;
import dev.javalitellm.proxy.keys.VirtualKey;
import dev.javalitellm.proxy.teams.TeamService;
import dev.javalitellm.proxy.users.UserService;
import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Spend accounting, off the request path: each call is logged and rolled up onto the key, then
 * cascaded to its team and user when present.
 */
@Service
public class SpendService {

    private static final Logger log = LoggerFactory.getLogger(SpendService.class);

    private final JdbcTemplate jdbc;
    private final KeyService keys;
    private final TeamService teams;
    private final UserService users;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    public SpendService(JdbcTemplate jdbc, KeyService keys, TeamService teams, UserService users) {
        this.jdbc = jdbc;
        this.keys = keys;
        this.teams = teams;
        this.users = users;
    }

    public void record(VirtualKey key, String modelGroup, ChatResponse response) {
        executor.submit(() -> {
            try {
                BigDecimal cost = response.costUsd() != null ? response.costUsd() : BigDecimal.ZERO;
                jdbc.update(
                        "INSERT INTO spend_logs (token_hash, model_group, model, prompt_tokens, completion_tokens,"
                                + " cost_usd) VALUES (?,?,?,?,?,?)",
                        key.tokenHash(),
                        modelGroup,
                        response.model(),
                        response.usage() != null ? response.usage().promptTokens() : null,
                        response.usage() != null ? response.usage().completionTokens() : null,
                        cost);
                if (cost.signum() > 0) {
                    keys.addSpend(key.tokenHash(), cost);
                    if (key.teamId() != null) {
                        teams.addSpend(key.teamId(), cost);
                    }
                    if (key.userId() != null) {
                        users.addSpend(key.userId(), cost);
                    }
                }
            } catch (RuntimeException e) {
                log.error("failed to record spend for key {}", key.tokenHash(), e);
            }
        });
    }
}
