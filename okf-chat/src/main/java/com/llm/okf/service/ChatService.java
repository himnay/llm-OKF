package com.llm.okf.service;

import com.llm.okf.model.ChatRequest;
import com.llm.okf.model.ChatResponse;
import com.llm.okf.model.NavContext;
import com.llm.okf.model.OkfFile;
import com.llm.okf.navigator.OkfNavigator;
import io.micrometer.core.annotation.Timed;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ChatService {

    private final ChatClient chatClient;
    private final OkfNavigator navigator;
    private final PromptTemplate systemPromptTemplate;

    public ChatService(
            ChatClient chatClient,
            OkfNavigator navigator,
            @Value("classpath:prompts/system.st") Resource systemPromptResource) {
        this.chatClient = chatClient;
        this.navigator = navigator;
        this.systemPromptTemplate = new PromptTemplate(systemPromptResource);
    }

    /**
     * Blocking OKF chat: navigate → load → answer.
     *
     * @param request the user's question
     * @return the LLM answer together with the file paths used as context
     */
    @Timed(value = "okf.chat", description = "OKF blocking chat — index read + file load + LLM call")
    public ChatResponse chat(ChatRequest request) {
        log.info("OKF query: {}", request.question());
        NavContext ctx = navigate(request.question());
        String answer = chatClient.prompt()
                .system(ctx.systemPrompt())
                .user(request.question())
                .call()
                .content();
        return new ChatResponse(answer, ctx.paths(), ctx.fileCount());
    }

    /**
     * Streaming OKF chat: same pipeline as {@link #chat}, emits tokens via SSE.
     *
     * @param request the user's question
     * @return token-by-token stream of the LLM response
     */
    @Timed(value = "okf.chat.stream", description = "OKF streaming chat — index read + file load + LLM stream")
    public Flux<String> stream(ChatRequest request) {
        log.info("OKF stream query: {}", request.question());
        NavContext ctx = navigate(request.question());
        return chatClient.prompt()
                .system(ctx.systemPrompt())
                .user(request.question())
                .stream()
                .content();
    }

    private NavContext navigate(String question) {
        String indexFile = navigator.readIndexFile();
        List<String> relevantFilesFound = navigator.findRelevantFiles(question, indexFile);
        log.info("Navigator selected files: {}", relevantFilesFound);
        List<OkfFile> files = navigator.loadFiles(relevantFilesFound);
        String systemPrompt = systemPromptTemplate.render(Map.of("context", buildContext(files)));
        return new NavContext(systemPrompt, relevantFilesFound, files.size());
    }

    private String buildContext(List<OkfFile> files) {
        return files.stream()
                .map(f -> "### %s (type: %s, tags: %s)\n\n%s".formatted(f.title(), f.type(), f.tags(), f.content()))
                .collect(Collectors.joining("\n\n---\n\n"));
    }

}
