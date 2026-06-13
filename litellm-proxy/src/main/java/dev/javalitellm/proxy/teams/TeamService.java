package dev.javalitellm.proxy.teams;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TeamService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public TeamService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Team create(String alias, BigDecimal maxBudget, Integer tpmLimit, Integer rpmLimit) {
        String teamId = "team-" + randomId();
        jdbc.update(
                "INSERT INTO teams (team_id, team_alias, max_budget, tpm_limit, rpm_limit) VALUES (?,?,?,?,?)",
                teamId,
                alias,
                maxBudget,
                tpmLimit,
                rpmLimit);
        return find(teamId).orElseThrow();
    }

    public Optional<Team> find(String teamId) {
        List<Team> rows = jdbc.query(
                "SELECT team_id, team_alias, max_budget, spend, tpm_limit, rpm_limit, blocked"
                        + " FROM teams WHERE team_id = ?",
                (rs, i) -> new Team(
                        rs.getString("team_id"),
                        rs.getString("team_alias"),
                        rs.getBigDecimal("max_budget"),
                        rs.getBigDecimal("spend"),
                        rs.getObject("tpm_limit", Integer.class),
                        rs.getObject("rpm_limit", Integer.class),
                        rs.getBoolean("blocked")),
                teamId);
        return rows.stream().findFirst();
    }

    public boolean update(String teamId, BigDecimal maxBudget, Integer tpmLimit, Integer rpmLimit, Boolean blocked) {
        Team existing = find(teamId).orElse(null);
        if (existing == null) {
            return false;
        }
        jdbc.update(
                "UPDATE teams SET max_budget = ?, tpm_limit = ?, rpm_limit = ?, blocked = ? WHERE team_id = ?",
                maxBudget == null ? existing.maxBudget() : maxBudget,
                tpmLimit == null ? existing.tpmLimit() : tpmLimit,
                rpmLimit == null ? existing.rpmLimit() : rpmLimit,
                blocked == null ? existing.blocked() : blocked,
                teamId);
        return true;
    }

    public boolean delete(String teamId) {
        return jdbc.update("DELETE FROM teams WHERE team_id = ?", teamId) > 0;
    }

    public void addSpend(String teamId, BigDecimal amount) {
        jdbc.update("UPDATE teams SET spend = spend + ? WHERE team_id = ?", amount, teamId);
    }

    private static String randomId() {
        byte[] random = new byte[12];
        RANDOM.nextBytes(random);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(random);
    }
}
