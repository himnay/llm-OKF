package com.llm.okf.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class WebConfig {

    /** Shared {@link RestClient} for GitHub API and raw content calls — pre-configured with JSON accept header and GitHub API version. */
    @Bean
    RestClient restClient() {
        return RestClient.builder()
                .defaultHeader("Accept", "application/json")
                .defaultHeader("User-Agent", "llm-OKF/1.0")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .build();
    }
}
