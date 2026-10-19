CREATE
EXTENSION IF NOT EXISTS vector;

CREATE TABLE repositories
(
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repo_url           TEXT        NOT NULL,
    ref                TEXT        NOT NULL,
    stable_url_ref_key TEXT        NOT NULL UNIQUE,
    status             VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    work_dir           TEXT        NOT NULL,
    commit_sha         VARCHAR(64),
    default_branch     VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE index_jobs
(
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    stage           VARCHAR(64)      NOT NULL,
    retry_count     INTEGER          NOT NULL DEFAULT 0,
    progress        DOUBLE PRECISION NOT NULL DEFAULT 0,
    files_processed INTEGER          NOT NULL DEFAULT 0,
    chunks_created  INTEGER          NOT NULL DEFAULT 0,
    warnings JSONB NOT NULL DEFAULT '[]'::jsonb,
    error           TEXT,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_index_jobs_repository_id ON index_jobs (repository_id);
CREATE INDEX idx_index_jobs_stage ON index_jobs (stage);

CREATE TABLE code_chunks
(
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id UUID NOT NULL REFERENCES repositories (id) ON DELETE CASCADE,
    path       TEXT    NOT NULL,
    symbol     TEXT,
    start_line INTEGER NOT NULL,
    end_line   INTEGER NOT NULL,
    content    TEXT    NOT NULL,
    embedding VECTOR(1536),
    search_vector TSVECTOR GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_code_chunks_line_range CHECK (start_line > 0 AND end_line >= start_line)
);

CREATE INDEX idx_code_chunks_repository_id ON code_chunks (repository_id);
CREATE INDEX idx_code_chunks_embedding_hnsw ON code_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX idx_code_chunks_search_vector ON code_chunks USING gin (search_vector);
