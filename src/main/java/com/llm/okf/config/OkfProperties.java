package com.llm.okf.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.okf")
public record OkfProperties(
        String knowledgeBasePath,
        int maxFilesPerQuery,
        String navigationModel) {
}
