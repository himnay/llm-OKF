package com.llm.okf.model;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.util.List;

public record SyncStatus(
        String status,
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant lastSync,
        int totalFiles,
        int newFiles,
        int updatedFiles,
        int skippedFiles,
        List<String> errors) {
}
