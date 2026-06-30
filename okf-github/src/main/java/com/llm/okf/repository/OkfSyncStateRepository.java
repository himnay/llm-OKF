package com.llm.okf.repository;

import com.llm.okf.model.OkfSyncState;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class OkfSyncStateRepository {

    private final OkfSyncStateJdbcRepository jdbcRepo;
    private final JdbcTemplate jdbc;

    public record SyncState(String sha, String llmModel) {}

    /**
     * Returns a map of {@code filePath → SyncState(sha, llmModel)} for all previously synced files.
     * Used to detect files changed by GitHub commit OR by extraction model change in application.yml.
     */
    public Map<String, SyncState> loadState(String repoUrl) {
        List<Object[]> rows = jdbc.query(
                "SELECT file_path, sha, llm_model FROM okf_sync_state WHERE repo_url = ?",
                (rs, i) -> new Object[]{rs.getString("file_path"), rs.getString("sha"), rs.getString("llm_model")},
                repoUrl
        );
        Map<String, SyncState> result = new LinkedHashMap<>();
        rows.forEach(r -> result.put((String) r[0], new SyncState((String) r[1], (String) r[2])));
        return result;
    }

    /**
     * Upserts a file record via Spring Data JDBC — triggers {@code @CreatedBy}/{@code @LastModifiedBy}
     * and {@code @CreatedDate}/{@code @LastModifiedDate} population automatically.
     * On first insert: all four audit fields are set.
     * On update: only {@code lastModifiedBy} and {@code lastModifiedAt} are refreshed.
     */
    public void saveOne(String filePath, String sha, String repoUrl, String llmModel) {
        OkfSyncState entity = jdbcRepo.findByRepoUrlAndFilePath(repoUrl, filePath)
                .orElseGet(() -> {
                    OkfSyncState s = new OkfSyncState();
                    s.setFilePath(filePath);
                    s.setRepoUrl(repoUrl);
                    return s;
                });
        entity.setSha(sha);
        entity.setLlmModel(llmModel);
        jdbcRepo.save(entity);
    }

    /**
     * Removes DB records for file paths that no longer exist in the GitHub repo.
     */
    public void deleteAll(List<String> filePaths, String repoUrl) {
        if (filePaths.isEmpty()) return;
        jdbc.batchUpdate(
                "DELETE FROM okf_sync_state WHERE repo_url = ? AND file_path = ?",
                filePaths.stream().map(p -> new Object[]{repoUrl, p}).toList()
        );
    }
}
