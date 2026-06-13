package dev.javalitellm.proxy.models;

import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Persistence for dynamically added models so they survive a restart. */
@Service
public class ModelDeploymentStore {

    public record StoredModel(String id, String modelName, String litellmParamsJson) {}

    private final JdbcTemplate jdbc;

    public ModelDeploymentStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void save(String id, String modelName, String litellmParamsJson) {
        jdbc.update(
                "INSERT INTO model_deployments (id, model_name, litellm_params) VALUES (?,?,?)",
                id,
                modelName,
                litellmParamsJson);
    }

    public List<StoredModel> all() {
        return jdbc.query(
                "SELECT id, model_name, litellm_params FROM model_deployments ORDER BY created_at",
                (rs, i) -> new StoredModel(
                        rs.getString("id"), rs.getString("model_name"), rs.getString("litellm_params")));
    }

    /** Deletes every stored deployment of a model group; returns how many rows were removed. */
    public int deleteByModelName(String modelName) {
        return jdbc.update("DELETE FROM model_deployments WHERE model_name = ?", modelName);
    }
}
