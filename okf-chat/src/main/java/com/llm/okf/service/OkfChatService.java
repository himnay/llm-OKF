package com.llm.okf.service;

import com.llm.okf.model.ChatRequest;
import com.llm.okf.model.ChatResponse;
import com.llm.okf.model.OkfFile;
import com.llm.okf.navigator.OkfNavigator;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OkfChatService {

    private final ChatClient chatClient;
    private final OkfNavigator navigator;

    /**
     * Blocking OKF chat: reads the index, navigates to relevant files, loads their full content,
     * and returns a single LLM-generated answer. Three phases: navigate → load → answer.
     *
     * @param request the user's question
     * @return the LLM answer together with the file paths used as context
     */
    @Timed(value = "okf.chat", description = "OKF blocking chat — index read + file load + LLM call")
    public ChatResponse chat(ChatRequest request) {
        log.info("OKF query: {}", request.question());

        // Phase 1: Navigate — find relevant files via index
        String index = navigator.loadIndex();
        List<String> relevantPaths = navigator.findRelevantFiles(request.question(), index);
        log.info("Navigator selected files: {}", relevantPaths);

        // Phase 2: Load full file content (no chunking, no embeddings)
        List<OkfFile> files = navigator.loadFiles(relevantPaths);
        String context = buildContext(files);

        // Phase 3: Answer with full context
        String systemPrompt = """
                You are a knowledgeable assistant with access to a structured knowledge base.

                KNOWLEDGE BASE CONTEXT:
                %s

                Answer the user's question using ONLY the information from the knowledge base above.
                Be specific and cite which file/section the information comes from when relevant.
                If the knowledge base does not contain relevant information, say so clearly.
                """.formatted(context);

        String answer = chatClient.prompt()
                .system(systemPrompt)
                .user(request.question())
                .call()
                .content();

        return new ChatResponse(answer, relevantPaths, files.size());
    }

    /**
     * Streaming OKF chat: same navigate → load → answer pipeline as {@link #chat}, but emits
     * LLM tokens as a {@link Flux} for Server-Sent Events delivery.
     *
     * @param request the user's question
     * @return token-by-token stream of the LLM response
     */
    @Timed(value = "okf.chat.stream", description = "OKF streaming chat — index read + file load + LLM stream")
    public Flux<String> stream(ChatRequest request) {
        log.info("OKF stream query: {}", request.question());

        String index = navigator.loadIndex();
        List<String> relevantPaths = navigator.findRelevantFiles(request.question(), index);
        log.info("Navigator selected files for stream: {}", relevantPaths);

        List<OkfFile> files = navigator.loadFiles(relevantPaths);
        String context = buildContext(files);

        String systemPrompt = """
                You are a knowledgeable assistant with access to a structured knowledge base.

                KNOWLEDGE BASE CONTEXT:
                %s

                Answer the user's question using ONLY the information from the knowledge base above.
                Cite which file/section the information comes from when relevant.
                """.formatted(context);

        return chatClient.prompt()
                .system(systemPrompt)
                .user(request.question())
                .stream()
                .content();
    }

    private String buildContext(List<OkfFile> files) {
        return files.stream()
                .map(f -> "### File: %s (type: %s)\n\n%s".formatted(f.path(), f.type(), f.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }
}
