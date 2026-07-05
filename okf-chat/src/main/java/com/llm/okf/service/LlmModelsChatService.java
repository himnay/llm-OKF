package com.llm.okf.service;

import com.llm.okf.model.CatalogChatResponse;
import com.llm.okf.model.ChatRequest;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Chat scoped to the Hugging Face model catalog only. No wiki navigation, no file loading —
 * the LLM answers exclusively through the catalog tools and must fill the structured
 * {@link CatalogChatResponse} contract (summary sentence + typed model rows), never
 * markdown-in-a-string.
 */
@Slf4j
@Service
public class LlmModelsChatService {

    private final ChatClient chatClient;
    private final String systemPrompt;
    private final String streamSystemPrompt;
    private final BeanOutputConverter<CatalogChatResponse> outputConverter =
            new BeanOutputConverter<>(CatalogChatResponse.class);

    public LlmModelsChatService(ChatClient chatClient,
                                @Value("classpath:prompts/llm-models-system.st") Resource systemPromptResource,
                                @Value("classpath:prompts/llm-models-stream-system.st") Resource streamSystemPromptResource) {
        this.chatClient = chatClient;
        try {
            this.systemPrompt = systemPromptResource.getContentAsString(StandardCharsets.UTF_8);
            this.streamSystemPrompt = streamSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot load llm-models system prompts", e);
        }
    }

    /**
     * Answers a question about the model catalog — the LLM is forced onto the catalog tools
     * and onto the JSON schema of {@link CatalogChatResponse}.
     *
     * @param request the user's question
     * @return structured answer; if the model emits unparseable JSON, its raw text is returned
     *         in {@code answer} with an empty {@code models} list rather than failing
     */
    @Timed(value = "okf.llm-models.chat", description = "Catalog chat — tools-only structured LLM answer over the HF model catalog")
    public CatalogChatResponse chat(ChatRequest request) {
        log.info("Catalog query: {}", request.question());
        String raw = chatClient.prompt()
                .system(systemPrompt)
                .user(request.question() + "\n\n" + outputConverter.getFormat())
                .call()
                .content();
        try {
            return outputConverter.convert(raw);
        } catch (Exception e) {
            log.warn("Catalog chat output not parseable as contract, returning raw text: {}", e.getMessage());
            return new CatalogChatResponse(raw, List.of());
        }
    }

    /**
     * Streaming catalog chat — same tools-grounded pipeline, but emits the markdown answer as
     * it is generated (SSE). LLM tokens are subword fragments ("arg", "max", "inc"); they are
     * re-chunked here so each SSE event carries one whole word — the classic typing effect.
     *
     * @param request the user's question
     * @return word-by-word stream of the markdown answer
     */
    @Timed(value = "okf.llm-models.chat.stream", description = "Catalog chat stream — tools-grounded word stream over the HF model catalog")
    public reactor.core.publisher.Flux<String> stream(ChatRequest request) {
        log.info("Catalog stream query: {}", request.question());
        return reactor.core.publisher.Flux.defer(() -> {
            StringBuilder buffer = new StringBuilder();
            return chatClient.prompt()
                    .system(streamSystemPrompt)
                    .user(request.question())
                    .stream()
                    .content()
                    .concatMap(chunk -> {
                        buffer.append(chunk);
                        int cut = lastWhitespace(buffer);
                        if (cut < 0) return reactor.core.publisher.Flux.<String>empty();
                        String ready = buffer.substring(0, cut + 1);
                        buffer.delete(0, cut + 1);
                        // split after each whitespace run — one word (with its separator) per event
                        return reactor.core.publisher.Flux.fromArray(ready.split("(?<=\\s)(?=\\S)"));
                    })
                    .concatWith(reactor.core.publisher.Flux.defer(() -> buffer.isEmpty()
                            ? reactor.core.publisher.Flux.empty()
                            : reactor.core.publisher.Flux.just(buffer.toString())));
        });
    }

    private static int lastWhitespace(StringBuilder buffer) {
        for (int i = buffer.length() - 1; i >= 0; i--) {
            if (Character.isWhitespace(buffer.charAt(i))) return i;
        }
        return -1;
    }

    /** Renders a structured answer as readable markdown — summary line plus a deterministic table. */
    public String toMarkdown(CatalogChatResponse response) {
        StringBuilder sb = new StringBuilder(response.answer() == null ? "" : response.answer()).append("\n");
        if (response.models() != null && !response.models().isEmpty()) {
            sb.append("\n| Model id | Type | Params | Runs locally | Good for |\n|---|---|---|---|---|\n");
            response.models().forEach(m -> sb.append("| ").append(nvl(m.id()))
                    .append(" | ").append(nvl(m.modelType()))
                    .append(" | ").append(m.paramsBillions() == null ? "?" : m.paramsBillions() + "B")
                    .append(" | ").append(m.runsLocally() == null ? "?" : m.runsLocally())
                    .append(" | ").append(nvl(m.goodFor())).append(" |\n"));
        }
        return sb.toString();
    }

    private static String nvl(String s) {
        return s == null ? "" : s.replace("|", "\\|");
    }
}
