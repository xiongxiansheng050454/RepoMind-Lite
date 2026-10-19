ALTER TABLE repositories
    ADD COLUMN current_job_id UUID REFERENCES index_jobs(id);
ALTER TABLE index_jobs
    ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE index_jobs
    ADD COLUMN lease_token UUID;
ALTER TABLE index_jobs
    ADD COLUMN lease_until TIMESTAMPTZ;
CREATE UNIQUE INDEX idx_one_active_index_job ON index_jobs (repository_id) WHERE stage NOT IN ('READY','FAILED');
CREATE TABLE index_outbox
(
    job_id UUID PRIMARY KEY REFERENCES index_jobs(id) ON DELETE CASCADE,
    next_publish_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_outbox_due ON index_outbox (next_publish_at);
