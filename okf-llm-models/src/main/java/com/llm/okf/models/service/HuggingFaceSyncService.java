package com.llm.okf.models.service;

import com.llm.okf.models.client.HuggingFaceClient;
import com.llm.okf.models.model.HfModel;
import com.llm.okf.models.model.LlmModelDoc;
import com.llm.okf.models.model.ModelSyncStatus;
import com.llm.okf.models.repository.LlmModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

/**
 * Orchestrates one catalog refresh: Hugging Face → enrich → MongoDB upsert → regenerate both
 * OKF sets (Pattern A materialized files, Pattern B query files). MongoDB is the source of
 * truth; vanished HF models are kept (requirements §9).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HuggingFaceSyncService {

    private final HuggingFaceClient client;
    private final ModelEnricher enricher;
    private final LlmModelRepository repository;
    private final OkfMaterializer materializer;
    private final OkfQueryFileWriter queryFileWriter;

    private volatile ModelSyncStatus lastStatus;

    public ModelSyncStatus sync() {
        Instant started = Instant.now();
        try {
            List<HfModel> fetched = client.fetchTopModels();
            List<LlmModelDoc> enriched = fetched.stream()
                    .map(hf -> enricher.enrich(hf, started))
                    .toList();
            repository.saveAll(enriched);

            List<LlmModelDoc> all = repository.findAll();
            int materialized = materializer.materialize(all);
            int queryFiles = queryFileWriter.writeQueryFiles();

            lastStatus = ModelSyncStatus.success(fetched.size(), enriched.size(),
                    materialized, queryFiles, started, Instant.now());
            log.info("LLM model sync done: fetched={}, upserted={}, materialized={}, queryFiles={}",
                    fetched.size(), enriched.size(), materialized, queryFiles);
        } catch (Exception e) {
            log.error("LLM model sync failed", e);
            lastStatus = ModelSyncStatus.failure(e.getMessage(), started, Instant.now());
        }
        return lastStatus;
    }

    public ModelSyncStatus getLastStatus() {
        return lastStatus;
    }
}
