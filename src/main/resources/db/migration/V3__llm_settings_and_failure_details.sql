ALTER TABLE index_jobs
    ADD COLUMN error_code VARCHAR(64);
ALTER TABLE index_jobs
    ADD COLUMN failed_stage VARCHAR(64);

CREATE TABLE app_settings
(
    setting_key   VARCHAR(64) PRIMARY KEY,
    setting_value TEXT        NOT NULL,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
