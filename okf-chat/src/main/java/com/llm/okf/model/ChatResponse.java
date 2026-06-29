package com.llm.okf.model;

import java.util.List;

public record ChatResponse(
        String answer,
        List<String> sourcesUsed,
        int filesLoaded) {
}
