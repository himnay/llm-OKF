package com.llm.okf.models.service;

import com.llm.okf.models.config.LlmModelsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pattern B consumption path. Reads a {@code type: query} OKF file, executes its frontmatter
 * query block against MongoDB, and returns the file content with live results appended as a
 * markdown table.
 *
 * <p>Safety: read-only {@code find} on an allow-listed collection; no operators that execute
 * server-side code; result size capped.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkfQueryResolver {

    private static final Set<String> ALLOWED_COLLECTIONS = Set.of("llm_models");
    private static final Set<String> FORBIDDEN_OPERATORS = Set.of("$where", "$function", "$accumulator", "$expr");
    private static final int MAX_LIMIT = 200;

    private final MongoTemplate mongoTemplate;
    private final LlmModelsProperties properties;

    /** Resolves a query OKF file (path relative to the query base path) into markdown with live results. */
    public String resolve(String relativePath) throws IOException {
        Path base = Path.of(properties.queryBasePath()).toAbsolutePath().normalize();
        Path file = base.resolve(relativePath).normalize();
        if (!file.startsWith(base)) {
            throw new IllegalArgumentException("Path escapes the query knowledge base: " + relativePath);
        }
        if (!Files.exists(file)) {
            throw new IllegalArgumentException("Query file not found: " + relativePath);
        }

        String content = Files.readString(file);
        Map<String, Object> query = extractQueryBlock(content);
        List<Document> results = execute(query);
        return content + renderResults(results, query);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> extractQueryBlock(String content) {
        if (!content.startsWith("---")) {
            throw new IllegalArgumentException("File has no OKF frontmatter");
        }
        int end = content.indexOf("\n---", 3);
        if (end < 0) {
            throw new IllegalArgumentException("Unterminated frontmatter block");
        }
        Map<String, Object> frontmatter = new Yaml().load(content.substring(4, end));
        if (!"query".equals(frontmatter.get("type"))) {
            throw new IllegalArgumentException("Not a query OKF file — frontmatter type must be 'query'");
        }
        Object query = frontmatter.get("query");
        if (!(query instanceof Map)) {
            throw new IllegalArgumentException("Missing 'query' block in frontmatter");
        }
        return (Map<String, Object>) query;
    }

    private List<Document> execute(Map<String, Object> query) {
        if (!"mongodb".equals(query.get("store"))) {
            throw new IllegalArgumentException("Unsupported store: " + query.get("store"));
        }
        String collection = String.valueOf(query.get("collection"));
        if (!ALLOWED_COLLECTIONS.contains(collection)) {
            throw new IllegalArgumentException("Collection not allowed: " + collection);
        }

        Document filter = parseSafe(query.get("filter"), new Document());
        Document projection = parseSafe(query.get("projection"), new Document());
        Document sort = parseSafe(query.get("sort"), new Document());

        int limit = query.get("limit") instanceof Number n ? n.intValue() : 50;
        BasicQuery basicQuery = new BasicQuery(filter, projection);
        basicQuery.setSortObject(sort);
        basicQuery.limit(Math.min(Math.max(limit, 1), MAX_LIMIT));

        return mongoTemplate.find(basicQuery, Document.class, collection);
    }

    private Document parseSafe(Object json, Document fallback) {
        if (json == null || String.valueOf(json).isBlank()) return fallback;
        String text = String.valueOf(json);
        String lower = text.toLowerCase();
        for (String op : FORBIDDEN_OPERATORS) {
            if (lower.contains(op)) {
                throw new IllegalArgumentException("Forbidden operator in query: " + op);
            }
        }
        return Document.parse(text);
    }

    private String renderResults(List<Document> results, Map<String, Object> query) {
        StringBuilder sb = new StringBuilder("\n\n## Results\n\n")
                .append("> Resolved live from MongoDB `").append(query.get("collection"))
                .append("` at ").append(Instant.now()).append(" — ").append(results.size()).append(" rows\n\n");
        if (results.isEmpty()) {
            return sb.append("_No matching documents._\n").toString();
        }

        List<String> columns = results.getFirst().keySet().stream().toList();
        sb.append("| ").append(String.join(" | ", columns)).append(" |\n");
        sb.append("|").append("---|".repeat(columns.size())).append("\n");
        for (Document row : results) {
            sb.append("| ");
            sb.append(String.join(" | ", columns.stream()
                    .map(c -> cell(row.get(c)))
                    .toList()));
            sb.append(" |\n");
        }
        return sb.toString();
    }

    private static String cell(Object value) {
        return value == null ? "" : value.toString().replace("|", "\\|").replace("\n", " ");
    }
}
