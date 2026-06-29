package com.llm.okf.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

@Configuration
@EnableConfigurationProperties(OkfProperties.class)
public class ChatClientConfig {

    /**
     * Primary chat client — wired to {@code spring.ai.ollama.chat.model} (fast model for navigation and answering).
     * Used by {@link com.llm.okf.navigator.OkfNavigator} and {@link com.llm.okf.service.OkfChatService}.
     */
    @Primary
    @Bean
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /**
     * Extraction chat client — wired to {@code app.okf.extraction-model} (quality model for OKF doc generation during sync).
     * Used by {@link com.llm.okf.service.GitHubSyncService} to convert raw source files into OKF knowledge documents.
     */
    @Bean
    ChatClient extractionChatClient(OllamaApi ollamaApi, OkfProperties properties) {
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .options(OllamaChatOptions.builder()
                        .model(properties.extractionModel())
                        .temperature(0.2)
                        .numCtx(32768)
                        .build())
                .build();
        return ChatClient.builder(model).build();
    }
}
