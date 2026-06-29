package com.llm.okf.navigator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.okf.config.OkfProperties;
import com.llm.okf.model.OkfFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OkfNavigator {

    private final ChatClient chatClient;
    private final OkfProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    private static final String FRONTMATTER_DELIMITER = "---";

    public String loadIndex() {
        return readResource(properties.knowledgeBasePath() + "/index.md");
    }

    public List<String> findRelevantFiles(String query, String index) {
        String navigationPrompt = """
                You are an OKF knowledge navigator. Given the index below and a user query,
                identify which files contain relevant information.

                INDEX:
                %s

                USER QUERY: %s

                Respond ONLY with a JSON array of file paths. Example: ["concepts/spring-ai-overview.md"]
                Include at most %d files. Only directly relevant files.
                Respond with ONLY the JSON array, no other text.
                """.formatted(index, query, properties.maxFilesPerQuery());

        String response = chatClient.prompt()
                .user(navigationPrompt)
                .call()
                .content();

        log.debug("Navigation LLM response: {}", response);
        return parseFilePaths(response);
    }

    public List<OkfFile> loadFiles(List<String> relativePaths) {
        return relativePaths.stream()
                .map(path -> {
                    String fullPath = properties.knowledgeBasePath() + "/" + path;
                    String content = readResource(fullPath);
                    return parseOkfFile(path, content);
                })
                .toList();
    }

    public List<OkfFile> listAllFiles() {
        try {
            PathMatchingResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
            String base = properties.knowledgeBasePath().replace("classpath:", "");
            String pattern = "classpath:" + base + "/**/*.md";
            Resource[] resources = resolver.getResources(pattern);
            return Arrays.stream(resources)
                    .filter(r -> {
                        String filename = r.getFilename();
                        return filename != null && !filename.equals("index.md");
                    })
                    .map(r -> {
                        try {
                            String content = r.getContentAsString(StandardCharsets.UTF_8);
                            String uri = r.getURI().toString();
                            int idx = uri.indexOf(base + "/");
                            String relativePath = idx >= 0
                                    ? uri.substring(idx + base.length() + 1)
                                    : r.getFilename();
                            return parseOkfFile(relativePath, content);
                        } catch (IOException e) {
                            throw new RuntimeException("Failed to read OKF file: " + r.getFilename(), e);
                        }
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list OKF files", e);
        }
    }

    private OkfFile parseOkfFile(String path, String content) {
        Map<String, Object> frontmatter = extractFrontmatter(content);
        String body = extractBody(content);
        return new OkfFile(
                path,
                (String) frontmatter.getOrDefault("title", path),
                (String) frontmatter.getOrDefault("type", "unknown"),
                castToStringList(frontmatter.getOrDefault("tags", List.of())),
                castToStringList(frontmatter.getOrDefault("related", List.of())),
                body
        );
    }

    private Map<String, Object> extractFrontmatter(String content) {
        if (!content.startsWith(FRONTMATTER_DELIMITER)) {
            return Map.of();
        }
        int end = content.indexOf(FRONTMATTER_DELIMITER, 3);
        if (end == -1) return Map.of();
        String yaml = content.substring(4, end).trim();
        Map<String, Object> result = new LinkedHashMap<>();
        for (String line : yaml.split("\n")) {
            int colon = line.indexOf(':');
            if (colon == -1) continue;
            String key = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            if (value.startsWith("[") && value.endsWith("]")) {
                String inner = value.substring(1, value.length() - 1);
                List<String> list = Arrays.stream(inner.split(","))
                        .map(s -> s.trim().replaceAll("^\"|\"$", ""))
                        .filter(s -> !s.isBlank())
                        .toList();
                result.put(key, list);
            } else {
                result.put(key, value.replaceAll("^\"|\"$", ""));
            }
        }
        return result;
    }

    private String extractBody(String content) {
        if (!content.startsWith(FRONTMATTER_DELIMITER)) return content;
        int end = content.indexOf(FRONTMATTER_DELIMITER, 3);
        if (end == -1) return content;
        return content.substring(end + 3).trim();
    }

    private String readResource(String path) {
        try {
            Resource resource = resourceLoader.getResource(path);
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read OKF file: {}", path);
            return "";
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> parseFilePaths(String json) {
        try {
            String trimmed = json.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start == -1 || end == -1 || start > end) {
                log.warn("No JSON array found in navigation response: {}", json);
                return List.of();
            }
            return objectMapper.readValue(trimmed.substring(start, end + 1), List.class);
        } catch (Exception e) {
            log.warn("Failed to parse file paths from LLM response: {}", json);
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> castToStringList(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
