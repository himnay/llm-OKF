package com.llm.okf.models.service;

import com.llm.okf.models.config.LlmModelsProperties;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.BasicQuery;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OkfQueryResolverTest {

    private static final String QUERY_FILE = """
            ---
            type: query
            title: Local-runnable models
            description: Live view
            tags: [llm-model, live-query]
            query:
              store: mongodb
              database: okf
              collection: llm_models
              filter: '{ "canRunLocally": true }'
              projection: '{ "name": 1, "modelType": 1 }'
              sort: '{ "downloads": -1 }'
              limit: 10
            ---

            # Local-runnable models
            """;

    @Mock
    private MongoTemplate mongoTemplate;

    @TempDir
    Path queryDir;

    private OkfQueryResolver resolver;

    @BeforeEach
    void setUp() {
        LlmModelsProperties properties = new LlmModelsProperties(
                "/tmp/unused", queryDir.toString(), "https://huggingface.co/api/models",
                50, 50, "downloads", 0, 0, 86400000L, false, false, "");
        resolver = new OkfQueryResolver(mongoTemplate, properties);
    }

    @Test
    @DisplayName("Resolves a query .md file against MongoDB and renders the results as a markdown table")
    void resolvesQueryFileAndRendersResultsTable() throws IOException {
        Files.writeString(queryDir.resolve("local.md"), QUERY_FILE);
        when(mongoTemplate.find(any(BasicQuery.class), eq(Document.class), eq("llm_models")))
                .thenReturn(List.of(
                        new Document("name", "Llama-3.1-8B").append("modelType", "text-generation"),
                        new Document("name", "phi-3-mini").append("modelType", "text-generation")));

        String resolved = resolver.resolve("local.md");

        assertThat(resolved).contains("# Local-runnable models");
        assertThat(resolved).contains("## Results");
        assertThat(resolved).contains("| name | modelType |");
        assertThat(resolved).contains("| Llama-3.1-8B | text-generation |");
        assertThat(resolved).contains("2 rows");
    }

    @Test
    @DisplayName("Rejects a query path that traverses outside the query directory")
    void rejectsPathTraversal() {
        assertThatThrownBy(() -> resolver.resolve("../../etc/passwd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escapes");
    }

    @Test
    @DisplayName("Rejects a query filename that does not exist in the query directory")
    void rejectsMissingFile() {
        assertThatThrownBy(() -> resolver.resolve("nope.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    @DisplayName("Rejects an OKF file whose frontmatter type is not 'query'")
    void rejectsNonQueryOkfFile() throws IOException {
        Files.writeString(queryDir.resolve("ref.md"), """
                ---
                type: reference
                title: Not a query
                ---
                body
                """);
        assertThatThrownBy(() -> resolver.resolve("ref.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("type must be 'query'");
    }

    @Test
    @DisplayName("Rejects a MongoDB filter containing forbidden server-side operators like $where")
    void rejectsServerSideCodeOperators() throws IOException {
        Files.writeString(queryDir.resolve("evil.md"), QUERY_FILE.replace(
                "'{ \"canRunLocally\": true }'",
                "'{ \"$where\": \"sleep(10000)\" }'"));
        assertThatThrownBy(() -> resolver.resolve("evil.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Forbidden operator");
    }

    @Test
    @DisplayName("Rejects a query targeting a collection that is not on the allowed list")
    void rejectsUnknownCollection() throws IOException {
        Files.writeString(queryDir.resolve("other.md"), QUERY_FILE.replace(
                "collection: llm_models", "collection: system.users"));
        assertThatThrownBy(() -> resolver.resolve("other.md"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Collection not allowed");
    }
}
