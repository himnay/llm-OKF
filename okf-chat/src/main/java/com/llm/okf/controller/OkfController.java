package com.llm.okf.controller;

import com.llm.okf.model.ChatRequest;
import com.llm.okf.model.ChatResponse;
import com.llm.okf.model.OkfFile;
import com.llm.okf.model.SyncStatus;
import com.llm.okf.navigator.OkfNavigator;
import com.llm.okf.service.GitHubSyncService;
import com.llm.okf.service.OkfChatService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/okf")
@Tag(name = "OKF", description = "Open Knowledge Format — GitHub-synced structured markdown knowledge base")
public class OkfController {

    private final OkfChatService chatService;
    private final OkfNavigator navigator;
    private final GitHubSyncService syncService;

    /** Blocking OKF chat — navigator selects relevant files from the index, LLM answers with full file content as context. */
    @PostMapping("/chat")
    @Operation(summary = "Ask a question — OKF navigator finds relevant files, LLM answers with full context")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    /** Streaming OKF chat — same pipeline as {@code /chat} but emits tokens via Server-Sent Events. */
    @Operation(summary = "Stream answer token-by-token via Server-Sent Events")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }

    /** Returns the raw {@code index.md} — the master navigation file that agents read first. */
    @GetMapping("/index")
    @Operation(summary = "Return the OKF index — master navigation file agents read first")
    public ResponseEntity<String> index() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(navigator.loadIndex());
    }

    /** Lists all OKF knowledge files under the knowledge base directory with parsed frontmatter metadata. */
    @GetMapping("/files")
    @Operation(summary = "List all OKF knowledge files with parsed frontmatter metadata")
    public List<OkfFile> files() {
        return navigator.listAllFiles();
    }

    /** Triggers a blocking GitHub sync — fetches changed files, generates OKF documents, and regenerates the index. */
    @PostMapping("/sync")
    @Operation(summary = "Trigger a manual GitHub sync — fetches latest files from the configured repo and regenerates the index")
    public SyncStatus sync() {
        return syncService.sync();
    }

    /** Returns the result of the last sync run, or 204 if no sync has occurred since startup. */
    @GetMapping("/sync/status")
    @Operation(summary = "Get the last sync status — shows totals, timestamps, and any errors")
    public ResponseEntity<SyncStatus> syncStatus() {
        SyncStatus status = syncService.getLastStatus();
        return status == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(status);
    }
}
