package com.llm.okf.models.service;

import com.llm.okf.models.client.HuggingFaceClient;
import com.llm.okf.models.model.HfModel;
import com.llm.okf.models.model.LlmModelDoc;
import com.llm.okf.models.model.ModelSyncStatus;
import com.llm.okf.models.repository.LlmModelRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
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

    private static final int UPSERT_BATCH = 1000;

    private final HuggingFaceClient client;
    private final ModelEnricher enricher;
    private final LlmModelRepository repository;
    private final MongoTemplate mongoTemplate;
    private final OkfMaterializer materializer;
    private final OkfQueryFileWriter queryFileWriter;

    private volatile ModelSyncStatus lastStatus;

    /**
     * Runs one full catalog refresh: fetch top models from Hugging Face, derive catalog fields,
     * bulk-upsert into MongoDB, then regenerate both OKF sets from the database.
     * Never throws — failures are captured in the returned status.
     *
     * @return the outcome of this run (counts on success, error message on failure)
     */
    public ModelSyncStatus sync() {
        Instant started = Instant.now();
        try {
            List<HfModel> fetched = client.fetchTopModels();
            List<LlmModelDoc> enriched = fetched.stream()
                    .map(hf -> enricher.enrich(hf, started))
                    .toList();
            bulkUpsert(enriched);

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

    // Single bulk round-trip per batch instead of one save() per document — matters at 10k+ models
    private void bulkUpsert(List<LlmModelDoc> models) {
        for (int from = 0; from < models.size(); from += UPSERT_BATCH) {
            List<LlmModelDoc> batch = models.subList(from, Math.min(from + UPSERT_BATCH, models.size()));
            BulkOperations ops = mongoTemplate.bulkOps(BulkOperations.BulkMode.UNORDERED, LlmModelDoc.class);
            batch.forEach(doc -> ops.replaceOne(
                    new Query(Criteria.where("_id").is(doc.id())),
                    doc,
                    org.springframework.data.mongodb.core.FindAndReplaceOptions.options().upsert()));
            ops.execute();
        }
    }

    /** Returns the result of the most recent sync run, or {@code null} if none has run since startup. */
    public ModelSyncStatus getLastStatus() {
        return lastStatus;
    }
}
