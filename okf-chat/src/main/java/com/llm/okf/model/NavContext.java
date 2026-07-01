package com.llm.okf.model;

import java.util.List;

public record NavContext(String systemPrompt, List<String> paths, int fileCount) {}
