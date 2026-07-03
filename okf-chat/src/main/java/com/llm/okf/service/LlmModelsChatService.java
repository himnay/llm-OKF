package com.llm.okf.service;

import com.llm.okf.model.ChatRequest;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Chat scoped to the Hugging Face model catalog only. No wiki navigation, no file loading —
 * the LLM answers exclusively through the catalog tools (search, OKF document, web enrichment),
 * so responses list real models from MongoDB and the flow is one LLM round shorter than
 * {@link ChatService}.
 */
@Slf4j
@Service
public class LlmModelsChatService {

    private final ChatClient chatClient;
    private final String systemPrompt;

    public LlmModelsChatService(ChatClient chatClient,
                                @Value("classpath:prompts/llm-models-system.st") Resource systemPromptResource) {
        this.chatClient = chatClient;
        try {
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load llm-models system prompt", e);
        }
    }

    /**
     * Answers a question about the model catalog — the LLM is forced onto the catalog tools
     * and renders search results as markdown tables.
     *
     * @param request the user's question
     * @return markdown answer grounded in the MongoDB catalog
     */
    @Timed(value = "okf.llm-models.chat", description = "Catalog chat — tools-only LLM answer over the HF model catalog")
    public String chat(ChatRequest request) {
        log.info("Catalog query: {}", request.question());
        return chatClient.prompt()
                .system(systemPrompt)
                .user(request.question())
                .call()
                .content();
    }
}
