package com.llm.okf.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record GitHubTreeResponse(
        String sha,
        List<GitHubTreeItem> tree,
        boolean truncated) {
}
