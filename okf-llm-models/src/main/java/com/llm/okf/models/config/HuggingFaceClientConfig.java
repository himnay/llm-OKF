package com.llm.okf.models.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
@EnableConfigurationProperties(LlmModelsProperties.class)
public class HuggingFaceClientConfig {

    /** Dedicated {@link RestClient} for the Hugging Face Hub API — optional bearer token for higher rate limits. */
    @Bean
    RestClient huggingFaceRestClient(LlmModelsProperties properties) {
        RestClient.Builder builder = RestClient.builder()
                .requestFactory(boundedTimeouts())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "llm-OKF/1.0");
        if (StringUtils.hasText(properties.hfToken())) {
            builder.defaultHeader("Authorization", "Bearer " + properties.hfToken());
        }
        return builder.build();
    }

    /** Bounded connect/read timeouts — the JDK client's default read timeout is infinite, so a hung upstream would pin the calling thread. */
    private static JdkClientHttpRequestFactory boundedTimeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }
}
