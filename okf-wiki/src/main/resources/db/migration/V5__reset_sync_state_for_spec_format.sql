-- Reset sync state so the scheduler regenerates all MD files in OKF spec v0.1 format.
-- Triggered by: field renames (source→resource), removal of related:[], addition of timestamp:,
-- index.md format change (full frontmatter→okf_version only, */-bullet style).
TRUNCATE TABLE okf_sync_state;
