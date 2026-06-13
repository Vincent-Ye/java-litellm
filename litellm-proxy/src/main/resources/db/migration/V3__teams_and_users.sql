CREATE TABLE teams (
    team_id    VARCHAR(64) PRIMARY KEY,
    team_alias VARCHAR(255),
    max_budget NUMERIC(18, 6),
    spend      NUMERIC(18, 8) NOT NULL DEFAULT 0,
    tpm_limit  INTEGER,
    rpm_limit  INTEGER,
    blocked    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE users (
    user_id    VARCHAR(64) PRIMARY KEY,
    user_alias VARCHAR(255),
    max_budget NUMERIC(18, 6),
    spend      NUMERIC(18, 8) NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

ALTER TABLE virtual_keys ADD COLUMN team_id VARCHAR(64);
ALTER TABLE virtual_keys ADD COLUMN user_id VARCHAR(64);
