package dev.javalitellm.proxy.keys;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Virtual key management. Only the SHA-256 hash of a key is stored; the plaintext is returned once
 * at generation time and cannot be recovered.
 */
@Service
public class KeyService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;

    public KeyService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns the plaintext key — the only time it is ever available. */
    public String generate(
            String alias,
            List<String> models,
            BigDecimal maxBudget,
            Duration validFor,
            Integer tpmLimit,
            Integer rpmLimit,
            String teamId,
            String userId) {
        byte[] random = new byte[24];
        RANDOM.nextBytes(random);
        String token = "sk-" + Base64.getUrlEncoder().withoutPadding().encodeToString(random);
        Instant expiresAt = validFor == null ? null : Instant.now().plus(validFor);
        jdbc.update(
                "INSERT INTO virtual_keys (token_hash, key_alias, models, max_budget, expires_at, tpm_limit,"
                        + " rpm_limit, team_id, user_id) VALUES (?,?,?,?,?,?,?,?,?)",
                hash(token),
                alias,
                models == null || models.isEmpty() ? null : String.join(",", models),
                maxBudget,
                expiresAt == null ? null : Timestamp.from(expiresAt),
                tpmLimit,
                rpmLimit,
                teamId,
                userId);
        return token;
    }

    public Optional<VirtualKey> find(String token) {
        return findByHash(hash(token));
    }

    public Optional<VirtualKey> findByHash(String tokenHash) {
        List<VirtualKey> rows = jdbc.query(
                "SELECT token_hash, key_alias, models, max_budget, spend, expires_at, blocked, tpm_limit, rpm_limit,"
                        + " team_id, user_id FROM virtual_keys WHERE token_hash = ?",
                (rs, i) -> new VirtualKey(
                        rs.getString("token_hash"),
                        rs.getString("key_alias"),
                        splitModels(rs.getString("models")),
                        rs.getBigDecimal("max_budget"),
                        rs.getBigDecimal("spend"),
                        rs.getTimestamp("expires_at") == null
                                ? null
                                : rs.getTimestamp("expires_at").toInstant(),
                        rs.getBoolean("blocked"),
                        rs.getObject("tpm_limit", Integer.class),
                        rs.getObject("rpm_limit", Integer.class),
                        rs.getString("team_id"),
                        rs.getString("user_id")),
                tokenHash);
        return rows.stream().findFirst();
    }

    public boolean update(String tokenHash, List<String> models, BigDecimal maxBudget, Boolean blocked) {
        VirtualKey existing = findByHash(tokenHash).orElse(null);
        if (existing == null) {
            return false;
        }
        jdbc.update(
                "UPDATE virtual_keys SET models = ?, max_budget = ?, blocked = ? WHERE token_hash = ?",
                models == null ? joinModels(existing.models()) : joinModels(models),
                maxBudget == null ? existing.maxBudget() : maxBudget,
                blocked == null ? existing.blocked() : blocked,
                tokenHash);
        return true;
    }

    public boolean delete(String tokenHash) {
        return jdbc.update("DELETE FROM virtual_keys WHERE token_hash = ?", tokenHash) > 0;
    }

    public void addSpend(String tokenHash, BigDecimal amount) {
        jdbc.update("UPDATE virtual_keys SET spend = spend + ? WHERE token_hash = ?", amount, tokenHash);
    }

    public static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<String> splitModels(String models) {
        return models == null || models.isBlank()
                ? List.of()
                : Arrays.stream(models.split(",")).map(String::trim).toList();
    }

    private static String joinModels(List<String> models) {
        return models == null || models.isEmpty() ? null : String.join(",", models);
    }
}
