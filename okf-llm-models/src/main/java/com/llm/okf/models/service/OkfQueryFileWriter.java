package com.llm.okf.models.service;

import com.llm.okf.models.config.LlmModelsProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/**
 * Pattern B — live view. Writes OKF files that contain only a MongoDB query definition, never
 * data. {@link OkfQueryResolver} executes the query when the file is consumed, so results always
 * reflect the current database state. The set of views is fixed; files are rewritten idempotently
 * on every sync.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkfQueryFileWriter {

    private static final String DEFAULT_PROJECTION =
            "{ \"name\": 1, \"modelType\": 1, \"paramsBillions\": 1, \"sizeCategory\": 1, \"goodFor\": 1 }";
    private static final String BY_DOWNLOADS = "{ \"downloads\": -1 }";

    private static final List<QueryDef> VIEWS = List.of(
            new QueryDef("all-local-runnable.md", "Local-runnable models",
                    "Live view — all LLM models that can run on local hardware",
                    "{ \"canRunLocally\": true }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100),
            new QueryDef("text-generation-models.md", "Text-generation models",
                    "Live view — models for text-based generative AI (chat, completion, code)",
                    "{ \"textGenerative\": true }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100),
            new QueryDef("multimodal-models.md", "Multimodal models",
                    "Live view — models that understand images plus text",
                    "{ \"multimodal\": true }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100),
            new QueryDef("audio-models.md", "Audio models",
                    "Live view — speech recognition, text-to-speech, and audio models",
                    "{ \"audio\": true }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100),
            new QueryDef("embedding-models.md", "Embedding models",
                    "Live view — models producing embeddings for search and RAG",
                    "{ \"embedding\": true }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100),
            new QueryDef("top-downloaded.md", "Top downloaded models",
                    "Live view — the 50 most-downloaded models on Hugging Face",
                    "{ }", "{ \"name\": 1, \"modelType\": 1, \"downloads\": 1, \"likes\": 1 }", BY_DOWNLOADS, 50),
            new QueryDef("small-models.md", "Small models (<3B params)",
                    "Live view — small models suited to constrained hardware",
                    "{ \"sizeCategory\": \"small\" }", DEFAULT_PROJECTION, BY_DOWNLOADS, 100));

    private final LlmModelsProperties properties;

    /** Writes the fixed set of query-definition OKF files plus a small index; returns the file count. */
    public int writeQueryFiles() throws IOException {
        Path base = Path.of(properties.queryBasePath());
        Files.createDirectories(base);
        Instant now = Instant.now();

        StringBuilder index = new StringBuilder("""
                ---
                okf_version: "0.1"
                ---

                # LLM Model Catalog — Live Views

                > Query-backed OKF files: each contains a MongoDB query, not data.
                > Resolve via `GET /api/v1/okf/llm-models/resolve?file=<name>` for live results.

                ## Views

                """);
        for (QueryDef view : VIEWS) {
            Files.writeString(base.resolve(view.filename()), render(view, now));
            index.append("* [").append(view.title()).append("](").append(view.filename())
                 .append(") - ").append(view.description()).append("\n");
        }
        Files.writeString(base.resolve("index.md"), index.append("\n").toString());

        log.info("Wrote {} query-view OKF files under {}", VIEWS.size(), base);
        return VIEWS.size();
    }

    private String render(QueryDef v, Instant now) {
        return """
                ---
                type: query
                title: %s
                description: %s, fetched from MongoDB at read time
                tags: [huggingface, llm-model, live-query]
                timestamp: %s
                query:
                  store: mongodb
                  database: okf
                  collection: llm_models
                  filter: '%s'
                  projection: '%s'
                  sort: '%s'
                  limit: %d
                ---

                # %s

                This is a live OKF view. The frontmatter `query` block is executed against MongoDB
                when this file is resolved — the data is NOT stored in this file.

                Resolve with: `GET /api/v1/okf/llm-models/resolve?file=%s`
                """.formatted(v.title(), v.description(), now,
                v.filter(), v.projection(), v.sort(), v.limit(),
                v.title(), v.filename());
    }

    private record QueryDef(String filename, String title, String description,
                            String filter, String projection, String sort, int limit) {}
}
