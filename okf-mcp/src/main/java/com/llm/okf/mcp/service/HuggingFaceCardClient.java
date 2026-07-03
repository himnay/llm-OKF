package com.llm.okf.mcp.service;

import com.llm.okf.mcp.config.McpProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.Optional;

/** Fetches a model's card (README.md) from the Hugging Face Hub — the authoritative public description of a model. */
@Slf4j
@Component
@RequiredArgsConstructor
public class HuggingFaceCardClient {

    private final RestClient huggingFaceRestClient;
    private final McpProperties properties;

    /** Returns the raw model card markdown, truncated to {@code card-max-chars}; empty when the model has no card. */
    public Optional<String> fetchCard(String modelId) {
        try {
            // URI.create keeps the slash in "author/model" literal — template expansion would
            // encode it to %2F, which the Hub rejects
            String card = huggingFaceRestClient.get()
                    .uri(URI.create("https://huggingface.co/" + modelId + "/raw/main/README.md"))
                    .retrieve()
                    .body(String.class);
            if (card == null || card.isBlank()) return Optional.empty();
            return Optional.of(card.length() > properties.cardMaxChars()
                    ? card.substring(0, properties.cardMaxChars()) + "\n\n[card truncated]"
                    : card);
        } catch (Exception e) {
            log.warn("No model card for {}: {}", modelId, e.getMessage());
            return Optional.empty();
        }
    }
}
