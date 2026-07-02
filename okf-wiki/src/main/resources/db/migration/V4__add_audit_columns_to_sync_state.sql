-- Add surrogate PK (BIGSERIAL generates ID for each row)
ALTER TABLE okf_sync_state ADD COLUMN id BIGSERIAL;

-- Add Spring Data audit columns (nullable initially so migration can backfill)
ALTER TABLE okf_sync_state
    ADD COLUMN created_by       VARCHAR(100),
    ADD COLUMN last_modified_by VARCHAR(100),
    ADD COLUMN created_at       TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_modified_at TIMESTAMP WITH TIME ZONE;

-- Backfill audit timestamps from existing synced_at
UPDATE okf_sync_state SET created_at = synced_at, last_modified_at = synced_at;

ALTER TABLE okf_sync_state
    ALTER COLUMN created_at       SET NOT NULL,
    ALTER COLUMN last_modified_at SET NOT NULL;

-- Swap primary key: drop composite, promote surrogate
ALTER TABLE okf_sync_state DROP CONSTRAINT okf_sync_state_pkey;
ALTER TABLE okf_sync_state ADD PRIMARY KEY (id);

-- Preserve logical uniqueness on the natural key
ALTER TABLE okf_sync_state
    ADD CONSTRAINT uq_sync_state_repo_file UNIQUE (repo_url, file_path);

-- Drop synced_at — superseded by Spring Data-managed last_modified_at
ALTER TABLE okf_sync_state DROP COLUMN synced_at;
