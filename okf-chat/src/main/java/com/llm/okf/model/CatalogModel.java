package com.llm.okf.model;

/** One model row in a structured catalog chat answer. */
public record CatalogModel(
        String id,
        String modelType,
        Double paramsBillions,
        Boolean runsLocally,
        String goodFor) {
}
