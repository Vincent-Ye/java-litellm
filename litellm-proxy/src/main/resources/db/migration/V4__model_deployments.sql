-- Dynamically added models (via /model/new); config.yaml models are not stored here.
CREATE TABLE model_deployments (
    id             VARCHAR(96) PRIMARY KEY,
    model_name     VARCHAR(255) NOT NULL,
    litellm_params TEXT NOT NULL,        -- raw JSON of the litellm_params block
    created_at     TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_model_deployments_name ON model_deployments (model_name);
