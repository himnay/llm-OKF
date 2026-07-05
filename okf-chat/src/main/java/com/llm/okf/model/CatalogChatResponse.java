package com.llm.okf.model;

import java.util.List;

/**
 * Structured contract for catalog chat answers: a short natural-language summary plus the
 * matching models as typed rows — never markdown embedded in a JSON string.
 */
public record CatalogChatResponse(
        String answer,
        List<CatalogModel> models) {

    public int count() {
        return models == null ? 0 : models.size();
    }
}
