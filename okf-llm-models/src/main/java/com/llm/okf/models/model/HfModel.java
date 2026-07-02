package com.llm.okf.models.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Instant;
import java.util.List;

/** Single model entry from the Hugging Face {@code /api/models} list endpoint. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record HfModel(
        String id,
        @JsonProperty("pipeline_tag") String pipelineTag,
        @JsonProperty("library_name") String libraryName,
        List<String> tags,
        Long downloads,
        Long likes,
        Instant createdAt,
        Instant lastModified) {
}
