package com.llm.okf.mcp.model;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

/**
 * Cached web-enriched model detail — generated once from the Hugging Face model card by the
 * local LLM, then served from MongoDB until the TTL expires or a refresh is forced.
 * {@code id} is the Hugging Face model id.
 */
@Document("llm_model_details")
public record ModelDetailDoc(
        @Id String id,
        String enrichedDescription,
        boolean cardFound,
        String generatorModel,
        Instant generatedAt) {
}
