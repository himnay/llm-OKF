package com.llm.okf.navigator;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.okf.config.OkfProperties;

import com.llm.okf.model.OkfFile;
import lombok.extern.slf4j.Slf4j;
import com.llm.okf.event.IndexSyncedEvent;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.Resource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OkfNavigator {

    private static final String INDEX_CACHE_KEY = "okf:index";
    private static final Duration INDEX_TTL = Duration.ofSeconds(60);

    private final ChatClient navigationChatClient;
    private final OkfProperties properties;
    private final PromptTemplate navigationPrompt;
    private final ObjectMapper objectMapper;
    private final StringRedisTemplate redis;

    public OkfNavigator(
            @Qualifier("navigationChatClient") ChatClient navigationChatClient,
            OkfProperties properties,
            @Value("classpath:prompts/navigation.st") Resource navigationPromptResource,
            ObjectMapper objectMapper,
            StringRedisTemplate redis) {
        this.navigationChatClient = navigationChatClient;
        this.properties = properties;
        this.navigationPrompt = new PromptTemplate(navigationPromptResource);
        this.objectMapper = objectMapper;
        this.redis = redis;
    }

    /** Returns the raw markdown content of {@code index.md}. Cached in Redis for 60s. */
    public String readIndexFile() {
        String cached = redis.opsForValue().get(INDEX_CACHE_KEY);
        if (cached != null)  {
            return cached;
        }
        String index = readFile(Path.of(properties.knowledgeBasePath(), "index.md"));
        if (!index.isBlank()) {
            redis.opsForValue().set(INDEX_CACHE_KEY, index, INDEX_TTL);
        }
        return index;
    }

    @EventListener
    public void onIndexSynced(IndexSyncedEvent event) {
        redis.delete(INDEX_CACHE_KEY);
        log.info("Redis cache evicted after sync: {}", INDEX_CACHE_KEY);
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

        String prompt = navigationPrompt.render(Map.of(
                "index", index,
                "query", query,
                "maxFilesPerQuery", properties.maxFilesPerQuery()
        ));

        String response = navigationChatClient.prompt()
                .user(prompt)
                .call()
                .content();

        log.debug("Navigation LLM response: {}", response);
        return parseFilePaths(response);
    }

    /**
     * Loads the full content of each OKF file — no chunking, no embeddings.
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
        log.debug("File Formatters : {}", fm);
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
        try {
            Map<String, Object> parsed = new Yaml().load(yaml);
            return parsed != null ? parsed : Map.of();
        } catch (Exception e) {
            log.warn("Failed to parse frontmatter YAML, skipping: {}", e.getMessage());
            return Map.of();
        }
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
        try {
            String trimmed = json.trim();
            int start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf(']');
            if (start < 0 || end < 0) {
                log.warn("No JSON array found in navigation response: {}", json);
                return List.of();
            }
            return objectMapper.readValue(trimmed.substring(start, end + 1), new TypeReference<>() {});
        } catch (Exception e) {
            log.warn("Failed to parse file paths from navigation response: {}", json);
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
