package com.llm.okf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeItem(
        String path,
        String sha,
        String type,
        Long size) {
}
