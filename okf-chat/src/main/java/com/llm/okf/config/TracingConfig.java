package com.llm.okf.config;

import io.opentelemetry.exporter.otlp.http.trace.OtlpHttpSpanExporter;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class TracingConfig {

    @Bean
    SpanExporter otlpHttpSpanExporter(@Value("${OTEL_ENDPOINT:http://localhost:4318}") String otelEndpoint) {
        return OtlpHttpSpanExporter.builder()
                .setEndpoint(otelEndpoint + "/v1/traces")
                .build();
    }
}
