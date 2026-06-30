package com.llm.okf.navigator;

import com.llm.okf.config.OkfProperties;
import com.llm.okf.model.OkfFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@Component
public class OkfNavigator {

    private final ChatClient navigationChatClient;
    private final OkfProperties properties;

    public OkfNavigator(@Qualifier("navigationChatClient") ChatClient navigationChatClient, OkfProperties properties) {
        this.navigationChatClient = navigationChatClient;
        this.properties = properties;
    }

    private static final Pattern FILE_PATH_PATTERN = Pattern.compile("\"([^\"]+\\.md)\"");

    /** Returns the raw markdown content of {@code index.md} — the agent's entry point into the knowledge base. */
    public String loadIndex() {
        return readFile(Path.of(properties.knowledgeBasePath(), "index.md"));
    }

    /**
     * Asks the LLM to select the most relevant OKF file paths from the index for the given query.
     *
     * @param query the user's natural-language question
     * @param index raw content of {@code index.md}
     * @return ordered list of relative file paths (at most {@code maxFilesPerQuery})
     */
    public List<String> findRelevantFiles(String query, String index) {
        if (index.isBlank()) {
            log.warn("Index is empty — knowledge base not synced yet");
            return List.of();
        }

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

        String response = navigationChatClient.prompt()
                .user(navigationPrompt)
                .call()
                .content();

        log.debug("Navigation LLM response: {}", response);
        return parseFilePaths(response);
    }

    /**
     * Loads the full content of each OKF file — no chunking, no embeddings.
     * The entire file is passed to the LLM as context, which is the core OKF principle.
     *
     * @param relativePaths paths relative to {@code knowledgeBasePath}
     * @return parsed OKF files including frontmatter metadata and body
     */
    public List<OkfFile> loadFiles(List<String> relativePaths) {
        Path base = Path.of(properties.knowledgeBasePath());
        return relativePaths.stream()
                .map(rel -> {
                    String content = readFile(base.resolve(rel));
                    return parseOkfFile(rel, content);
                })
                .toList();
    }

    /** Lists every OKF knowledge file under {@code knowledgeBasePath}, excluding the index and dotfiles. */
    public List<OkfFile> listAllFiles() {
        Path base = Path.of(properties.knowledgeBasePath());
        if (!Files.exists(base)) return List.of();
        try {
            return Files.walk(base)
                    .filter(p -> p.toString().endsWith(".md"))
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .filter(p -> !p.getFileName().toString().equals("index.md"))
                    .sorted()
                    .map(p -> {
                        String content = readFile(p);
                        String rel = base.relativize(p).toString().replace('\\', '/');
                        return parseOkfFile(rel, content);
                    })
                    .toList();
        } catch (IOException e) {
            throw new RuntimeException("Failed to list OKF files", e);
        }
    }

    private OkfFile parseOkfFile(String path, String content) {
        Map<String, Object> fm = extractFrontmatter(content);
        String body = extractBody(content);
        return new OkfFile(
                path,
                (String) fm.getOrDefault("title", path),
                (String) fm.getOrDefault("type", "unknown"),
                castToStringList(fm.getOrDefault("tags", List.of())),
                castToStringList(fm.getOrDefault("related", List.of())),
                body
        );
    }

    private Map<String, Object> extractFrontmatter(String content) {
        if (!content.startsWith("---")) return Map.of();
        int end = content.indexOf("---", 3);
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
        if (!content.startsWith("---")) return content;
        int end = content.indexOf("---", 3);
        if (end == -1) return content;
        return content.substring(end + 3).trim();
    }

    private String readFile(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.warn("Could not read OKF file: {}", path);
            return "";
        }
    }

    private List<String> parseFilePaths(String json) {
        Matcher matcher = FILE_PATH_PATTERN.matcher(json);
        List<String> paths = new java.util.ArrayList<>();
        while (matcher.find()) {
            paths.add(matcher.group(1));
        }
        if (paths.isEmpty()) {
            log.warn("No file paths found in navigation response: {}", json);
        }
        return paths;
    }

    @SuppressWarnings("unchecked")
    private List<String> castToStringList(Object value) {
        if (value instanceof List<?> list) {
            return (List<String>) list;
        }
        return List.of();
    }
}
