package com.llm.okf.repository;

import com.llm.okf.model.OkfSyncState;
import org.springframework.data.repository.ListCrudRepository;

import java.util.Optional;

public interface OkfSyncStateJdbcRepository extends ListCrudRepository<OkfSyncState, Long> {

    Optional<OkfSyncState> findByRepoUrlAndFilePath(String repoUrl, String filePath);
}
