package com.llm.okf.models.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(LlmModelsProperties.class)
public class HuggingFaceClientConfig {

    /** Dedicated {@link RestClient} for the Hugging Face Hub API — optional bearer token for higher rate limits. */
    @Bean
    RestClient huggingFaceRestClient(LlmModelsProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "llm-OKF/1.0");
        if (StringUtils.hasText(properties.hfToken())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.hfToken());
        }
        return builder.build();
    }
}
