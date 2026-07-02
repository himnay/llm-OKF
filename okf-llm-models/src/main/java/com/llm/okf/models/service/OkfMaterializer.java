package com.llm.okf.models.service;

import com.llm.okf.models.config.LlmModelsProperties;
import com.llm.okf.models.model.LlmModelDoc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pattern A — materialized view. Copies every MongoDB model document into a self-contained OKF
 * markdown file (data embedded in the file). Files are disposable: each sync rewrites them from
 * the database, grouped in one subfolder per model type, plus a grouped {@code index.md}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OkfMaterializer {

    private final LlmModelsProperties properties;

    /** Writes one OKF file per model under the knowledge base path; returns the number of files written. */
    public int materialize(List<LlmModelDoc> models) throws IOException {
        Path base = Path.of(properties.knowledgeBasePath());
        Files.createDirectories(base);

        Map<String, List<IndexEntry>> bySection = new TreeMap<>();
        for (LlmModelDoc model : models) {
            String section = sanitize(model.modelType() == null ? "other" : model.modelType());
            Path dir = base.resolve(section);
            Files.createDirectories(dir);
            String filename = sanitize(model.id().replace("/", "__")) + ".md";
            Files.writeString(dir.resolve(filename), render(model));
            bySection.computeIfAbsent(section, k -> new ArrayList<>())
                     .add(new IndexEntry(model.name(), section + "/" + filename, description(model)));
        }

        writeIndex(base, bySection, models.size());
        log.info("Materialized {} OKF model files under {}", models.size(), base);
        return models.size();
    }

    private String render(LlmModelDoc m) {
        String params = m.paramsBillions() == null ? "unknown"
                : (m.paramsBillions() < 1 ? Math.round(m.paramsBillions() * 1000) + "M" : trimZero(m.paramsBillions()) + "B");
        String localNote = Boolean.TRUE.equals(m.canRunLocally())
                ? "**yes** (" + params + " params — fits consumer hardware, e.g. via Ollama or llama.cpp)"
                : "no (too large or not packaged for local inference)";

        return """
                ---
                type: reference
                title: %s
                description: %s
                resource: https://huggingface.co/%s
                tags: [huggingface, llm-model, %s%s]
                timestamp: %s
                ---

                # %s

                ## Overview
                | Property      | Value |
                |---------------|-------|
                | Author        | %s |
                | Model type    | %s |
                | Library       | %s |
                | License       | %s |
                | Parameters    | %s |
                | Size category | %s |
                | Downloads     | %,d |
                | Likes         | %,d |

                ## Capabilities
                - Text-based generative AI: %s
                - Multimodal: %s
                - Audio: %s
                - Vision: %s
                - Embedding: %s

                ## Local Execution
                Can run locally: %s

                ## Good For
                %s

                ## Database Details
                | Property    | Value |
                |-------------|-------|
                | Store       | MongoDB |
                | Database    | okf |
                | Collection  | llm_models |
                | Document id | %s |
                | Synced at   | %s |
                """.formatted(
                m.name(), description(m), m.id(),
                m.modelType(), Boolean.TRUE.equals(m.canRunLocally()) ? ", local-capable" : "",
                m.syncedAt(),
                m.name(),
                m.author(), m.modelType(), nvl(m.libraryName()), m.license(), params, m.sizeCategory(),
                m.downloads() == null ? 0 : m.downloads(), m.likes() == null ? 0 : m.likes(),
                yesNo(m.textGenerative()), yesNo(m.multimodal()), yesNo(m.audio()),
                yesNo(m.vision()), yesNo(m.embedding()),
                localNote,
                m.goodFor(),
                m.id(), m.syncedAt());
    }

    private String description(LlmModelDoc m) {
        String params = m.paramsBillions() == null ? "" : ", " + trimZero(m.paramsBillions()) + "B params";
        String local = Boolean.TRUE.equals(m.canRunLocally()) ? ", runs locally" : "";
        return m.modelType() + " model by " + m.author() + params + local + " — " + m.goodFor();
    }

    // OKF spec §6: `* [Title](path) - description` lines under one section heading per model type
    private void writeIndex(Path base, Map<String, List<IndexEntry>> bySection, int total) throws IOException {
        StringBuilder sb = new StringBuilder("""
                ---
                okf_version: "0.1"
                ---

                # LLM Model Catalog (Hugging Face)

                > Materialized from MongoDB `llm_models` — %d models, refreshed on every sync.
                > Each entry links to an OKF knowledge document — read the description to navigate intelligently.

                """.formatted(total));
        bySection.forEach((section, entries) -> {
            sb.append("## ").append(section).append("\n\n");
            entries.forEach(e -> sb.append("* [").append(e.title()).append("](").append(e.path())
                    .append(") - ").append(e.description()).append("\n"));
            sb.append("\n");
        });
        Files.writeString(base.resolve("index.md"), sb.toString());
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static String yesNo(Boolean b) {
        return Boolean.TRUE.equals(b) ? "**yes**" : "no";
    }

    private static String nvl(String s) {
        return s == null ? "unknown" : s;
    }

    private static String trimZero(double d) {
        return d == Math.floor(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private record IndexEntry(String title, String path, String description) {}
}
