package com.llm.okf.models.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

/**
 * Enriched LLM model catalog entry — the MongoDB source of truth for both OKF patterns.
 * {@code id} is the Hugging Face model id (e.g. {@code meta-llama/Llama-3.1-8B-Instruct});
 * re-sync upserts in place, vanished models are kept.
 */
@Document("llm_models")
public record LlmModelDoc(
        @Id String id,
        String name,
        String author,
        String modelType,
        String libraryName,
        List<String> tags,
        String license,
        Double paramsBillions,
        String sizeCategory,
        Boolean canRunLocally,
        Boolean textGenerative,
        Boolean multimodal,
        Boolean audio,
        Boolean vision,
        Boolean embedding,
        String goodFor,
        Long downloads,
        Long likes,
        Instant createdAt,
        Instant lastModified,
        Instant syncedAt) {
}
