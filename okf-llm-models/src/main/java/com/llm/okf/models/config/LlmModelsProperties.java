package com.llm.okf.models.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.okf.llm-models")
public record LlmModelsProperties(
        String knowledgeBasePath,
        String queryBasePath,
        String apiUrl,
        int minModels,
        int pageSize,
        String sort,
        long minLikes,
        long minDownloads,
        long intervalMs,
        boolean enabled,
        boolean syncOnStartup,
        String hfToken) {
}
