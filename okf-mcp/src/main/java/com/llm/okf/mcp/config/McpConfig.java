package com.llm.okf.mcp.config;

import com.llm.okf.mcp.service.LlmModelMcpTools;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(McpProperties.class)
public class McpConfig {

    /** Registers the OKF model tools with the MCP server — external agents call them over the /sse endpoint. */
    @Bean
    ToolCallbackProvider okfModelToolCallbacks(LlmModelMcpTools tools) {
        return MethodToolCallbackProvider.builder().toolObjects(tools).build();
    }

    /**
     * Dedicated chat client for web-enrichment summarization — separate bean so the tools do not
     * depend on the primary chat client (which itself carries these tools; sharing it would be a cycle).
     */
    @Bean
    ChatClient enrichmentChatClient(OllamaApi ollamaApi, McpProperties properties, ObservationRegistry observationRegistry) {
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .observationRegistry(observationRegistry)
                .options(OllamaChatOptions.builder()
                        .model(properties.enrichmentModel())
                        .temperature(0.3)
                        .numCtx(16384)
                        // long-form profile output (1200-1800 words) — don't let the default cut it short
                        .numPredict(4096)
                        .build())
                .build();
        return ChatClient.builder(model).build();
    }
}
