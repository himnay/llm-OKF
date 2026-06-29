package com.llm.okf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.okf")
public record OkfProperties(
        String knowledgeBasePath,
        int maxFilesPerQuery,
        String navigationModel,
        String extractionModel,
        SyncConfig sync) {

    public record SyncConfig(
            String githubUrl,
            String githubToken,
            long intervalMs,
            boolean enabled,
            boolean syncOnStartup,
            boolean useLlmSummarization) {
    }
}
