package com.llm.okf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

@Configuration
public class WebConfig {

    /** Shared {@link RestClient} for GitHub API and raw content calls — pre-configured with JSON accept header and GitHub API version. */
    @Bean
    RestClient restClient() {
        return RestClient.builder()
                .requestFactory(boundedTimeouts())
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "llm-OKF/1.0")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }

    /** Bounded connect/read timeouts — the JDK client's default read timeout is infinite, so a hung upstream would pin the calling thread. */
    private static JdkClientHttpRequestFactory boundedTimeouts() {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
        factory.setReadTimeout(Duration.ofSeconds(30));
        return factory;
    }
}
