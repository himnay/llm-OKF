package com.llm.okf.model;

public record IngestResponse(
        String message,
        String outputPath,
        int pagesProcessed) {
}
