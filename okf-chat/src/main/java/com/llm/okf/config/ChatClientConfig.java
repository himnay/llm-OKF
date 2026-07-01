package com.llm.okf.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.observation.ObservationRegistry;
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

    @Bean
    ObjectMapper objectMapper() {
        return new ObjectMapper();
    }

    /** Primary chat client — quality model for answering user questions. */
    @Bean
    @Primary
    ChatClient chatClient(ChatModel chatModel) {
        return ChatClient.builder(chatModel).build();
    }

    /** Navigation client — fast small model, only selects relevant files from index. */
    @Bean
    ChatClient navigationChatClient(OllamaApi ollamaApi, OkfProperties properties, ObservationRegistry observationRegistry) {
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .observationRegistry(observationRegistry)
                .options(OllamaChatOptions.builder()
                        .model(properties.navigationModel())
                        .temperature(0.0)
                        .numCtx(8192)
                        .build())
                .build();
        return ChatClient.builder(model).build();
    }

    /** Extraction client — quality model for converting raw source files into OKF docs during sync. */
    @Bean
    ChatClient extractionChatClient(OllamaApi ollamaApi, OkfProperties properties, ObservationRegistry observationRegistry) {
        OllamaChatModel model = OllamaChatModel.builder()
                .ollamaApi(ollamaApi)
                .observationRegistry(observationRegistry)
                .options(OllamaChatOptions.builder()
                        .model(properties.extractionModel())
                        .temperature(0.2)
                        .numCtx(32768)
                        .build())
                .build();
        return ChatClient.builder(model).build();
    }
}
