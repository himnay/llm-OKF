package com.llm.okf.mcp.service;

import com.llm.okf.mcp.config.McpProperties;
import com.llm.okf.mcp.model.ModelDetailDoc;
import com.llm.okf.mcp.repository.ModelDetailRepository;
import com.llm.okf.models.model.LlmModelDoc;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Produces the "rich description" of a model: what it does, how it compares to peers, best use
 * cases, limitations. Sources the model's Hugging Face card from the internet, has the local LLM
 * write the analysis, and caches the result in MongoDB ({@code llm_model_details}) so repeated
 * queries are instant.
 */
@Slf4j
@Service
public class ModelWebEnricher {

    private final HuggingFaceCardClient cardClient;
    private final ModelDetailRepository detailRepository;
    private final ChatClient enrichmentChatClient;
    private final McpProperties properties;
    private final String systemPrompt;

    public ModelWebEnricher(HuggingFaceCardClient cardClient,
                            ModelDetailRepository detailRepository,
                            @Qualifier("enrichmentChatClient") ChatClient enrichmentChatClient,
                            McpProperties properties,
                            @Value("classpath:prompts/enrichment.st") Resource systemPromptResource) {
        this.cardClient = cardClient;
        this.detailRepository = detailRepository;
        this.enrichmentChatClient = enrichmentChatClient;
        this.properties = properties;
        try {
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load enrichment system prompt", e);
        }
    }

    /** Returns the enriched detail for a model — served from cache when fresh, otherwise regenerated. */
    public ModelDetailDoc enrich(LlmModelDoc model, boolean refresh) {
        if (!refresh) {
            Optional<ModelDetailDoc> cached = detailRepository.findById(model.id())
                    .filter(d -> d.generatedAt().isAfter(Instant.now().minus(Duration.ofHours(properties.cacheTtlHours()))));
            if (cached.isPresent()) {
                log.info("Enrichment cache hit for {}", model.id());
                return cached.get();
            }
        }

        Optional<String> card = cardClient.fetchCard(model.id());
        String description = generate(model, card.orElse(null));
        ModelDetailDoc detail = new ModelDetailDoc(
                model.id(), description, card.isPresent(), properties.enrichmentModel(), Instant.now());
        detailRepository.save(detail);
        log.info("Enriched {} (cardFound={})", model.id(), card.isPresent());
        return detail;
    }

    private String generate(LlmModelDoc model, String card) {
        String metadata = """
                Model id: %s
                Type: %s | Library: %s | License: %s
                Parameters: %s | Size category: %s | Runs locally: %s
                Capabilities: textGenerative=%s multimodal=%s audio=%s vision=%s embedding=%s
                Downloads: %s | Likes: %s
                """.formatted(model.id(), model.modelType(), model.libraryName(), model.license(),
                model.paramsBillions(), model.sizeCategory(), model.canRunLocally(),
                model.textGenerative(), model.multimodal(), model.audio(), model.vision(), model.embedding(),
                model.downloads(), model.likes());

        String source = card == null
                ? "No model card is available — analyze from the metadata and your own knowledge of this model family."
                : "Model card from huggingface.co:\n\n" + card;

        return enrichmentChatClient.prompt()
                .system(systemPrompt)
                .user(metadata + "\n\n" + source)
                .call()
                .content();
    }
}
