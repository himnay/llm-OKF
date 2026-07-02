CREATE TABLE okf_sync_state (
    file_path   VARCHAR(1000)               NOT NULL,
    repo_url    VARCHAR(500)                NOT NULL,
    sha         VARCHAR(40)                 NOT NULL,
    synced_at   TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_url, file_path)
);

CREATE INDEX idx_sync_state_repo ON okf_sync_state (repo_url);
