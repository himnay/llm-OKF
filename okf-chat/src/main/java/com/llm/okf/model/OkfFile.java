package com.llm.okf.model;

import java.util.List;

public record OkfFile(
        String path,
        String title,
        String type,
        List<String> tags,
        List<String> related,
        String content) {
}
