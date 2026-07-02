package com.llm.okf.controller;

import com.llm.okf.models.model.ModelSyncStatus;
import com.llm.okf.models.service.HuggingFaceSyncService;
import com.llm.okf.models.service.OkfQueryResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/okf/llm-models")
@Tag(name = "OKF LLM Models", description = "Hugging Face model catalog — MongoDB-backed OKF files (materialized + live-query patterns)")
public class LlmModelsController {

    private final HuggingFaceSyncService syncService;
    private final OkfQueryResolver queryResolver;

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
