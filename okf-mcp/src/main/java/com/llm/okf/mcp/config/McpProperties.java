package com.llm.okf.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.okf.mcp")
public record McpProperties(
        String enrichmentModel,
        int cardMaxChars,
        long cacheTtlHours) {
}
