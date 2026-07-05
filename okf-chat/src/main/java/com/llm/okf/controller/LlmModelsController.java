package com.llm.okf.controller;

import com.llm.okf.model.ChatRequest;
import com.llm.okf.models.model.ModelSyncStatus;
import com.llm.okf.models.service.HuggingFaceSyncService;
import com.llm.okf.models.service.OkfQueryResolver;
import com.llm.okf.service.LlmModelsChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/okf/llm-models")
@Tag(name = "OKF LLM Models", description = "Hugging Face model catalog — MongoDB-backed OKF files (materialized + live-query patterns)")
public class LlmModelsController {

    private final HuggingFaceSyncService syncService;
    private final OkfQueryResolver queryResolver;
    private final LlmModelsChatService catalogChatService;

    /** Catalog-only chat — structured JSON contract: {@code {answer, models[{id,modelType,paramsBillions,runsLocally,goodFor}]}}. */
    @PostMapping("/chat")
    @Operation(summary = "Ask about LLM models — structured JSON answer (summary + typed model rows) from the MongoDB catalog")
    public com.llm.okf.model.CatalogChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return catalogChatService.chat(request);
    }

    /** Same catalog chat rendered as readable markdown table — send {@code Accept: text/markdown}. */
    @PostMapping(value = "/chat", produces = MediaType.TEXT_MARKDOWN_VALUE)
    @Operation(summary = "Ask about LLM models — markdown table response (Accept: text/markdown)")
    public String chatMarkdown(@Valid @RequestBody ChatRequest request) {
        return catalogChatService.toMarkdown(catalogChatService.chat(request));
    }

    /** Streaming catalog chat — markdown tokens via Server-Sent Events as the answer is generated. */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Stream a catalog answer token-by-token via Server-Sent Events")
    public reactor.core.publisher.Flux<String> chatStream(@Valid @RequestBody ChatRequest request) {
        return catalogChatService.stream(request);
    }

    /** Resolves a Pattern B query OKF file — executes its MongoDB query and returns markdown with live results inlined. */
    @GetMapping(value = "/resolve", produces = MediaType.TEXT_MARKDOWN_VALUE)
    @Operation(summary = "Resolve a query OKF file — executes the embedded MongoDB query and inlines live results")
    public String resolve(@RequestParam String file) throws IOException {
        return queryResolver.resolve(file);
    }

    /** Triggers a blocking catalog sync — fetches models from Hugging Face, upserts MongoDB, regenerates both OKF sets. */
    @PostMapping("/sync")
    @Operation(summary = "Trigger a manual Hugging Face catalog sync — refreshes MongoDB and regenerates OKF files")
    public ModelSyncStatus sync() {
        return syncService.sync();
    }

    /** Returns the result of the last catalog sync, or 204 if no sync has occurred since startup. */
    @GetMapping("/sync/status")
    @Operation(summary = "Get the last catalog sync status — counts, timestamps, and any error")
    public ResponseEntity<ModelSyncStatus> syncStatus() {
        ModelSyncStatus status = syncService.getLastStatus();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }
}
