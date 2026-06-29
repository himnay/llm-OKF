package com.llm.okf.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class OkfSyncStateRepository {

    private final JdbcTemplate jdbc;

    /**
     * Returns a map of {@code filePath → sha} for all previously synced files from the given repo.
     * Used to detect which files have changed since the last sync.
     *
     * @param repoUrl the GitHub repo URL used as the partition key
     */
    public Map<String, String> loadShas(String repoUrl) {
        List<String[]> rows = jdbc.query(
                "SELECT file_path, sha FROM okf_sync_state WHERE repo_url = ?",
                (rs, i) -> new String[]{rs.getString("file_path"), rs.getString("sha")},
                repoUrl
        );
        Map<String, String> result = new LinkedHashMap<>();
        rows.forEach(r -> result.put(r[0], r[1]));
        return result;
    }

    /**
     * Upserts a single file SHA immediately after it is processed — ensures crash recovery:
     * a restart will skip this file on the next sync because its SHA is already persisted.
     */
    public void saveOne(String filePath, String sha, String repoUrl) {
        jdbc.update("""
                INSERT INTO okf_sync_state (file_path, repo_url, sha, synced_at)
                VALUES (?, ?, ?, NOW())
                ON CONFLICT (repo_url, file_path) DO UPDATE SET sha = EXCLUDED.sha, synced_at = NOW()
                """, filePath, repoUrl, sha);
    }

    /**
     * Removes DB records for file paths that no longer exist in the GitHub repo.
     *
     * @param filePaths paths to remove
     * @param repoUrl   the GitHub repo URL partition key
     */
    public void deleteAll(List<String> filePaths, String repoUrl) {
        if (filePaths.isEmpty()) return;
        jdbc.batchUpdate(
                "DELETE FROM okf_sync_state WHERE repo_url = ? AND file_path = ?",
                filePaths.stream().map(p -> new Object[]{repoUrl, p}).toList()
        );
    }

    /**
     * Upserts file SHAs into {@code okf_sync_state}. Uses {@code ON CONFLICT DO UPDATE} so each call
     * is idempotent — safe to re-run after a partial sync failure.
     *
     * @param shas    map of {@code filePath → sha} for all files processed in this sync run
     * @param repoUrl the GitHub repo URL used as the partition key
     */
    public void saveAll(Map<String, String> shas, String repoUrl) {
        if (shas.isEmpty()) return;
        jdbc.batchUpdate(
                """
                INSERT INTO okf_sync_state (file_path, repo_url, sha, synced_at)
                VALUES (?, ?, ?, NOW())
                ON CONFLICT (repo_url, file_path)
                DO UPDATE SET sha = EXCLUDED.sha, synced_at = NOW()
                """,
                shas.entrySet().stream()
                        .map(e -> new Object[]{e.getKey(), repoUrl, e.getValue()})
                        .toList()
        );
    }
}
