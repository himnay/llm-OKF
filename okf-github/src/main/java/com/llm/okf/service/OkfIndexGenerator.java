package com.llm.okf.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class OkfIndexGenerator {

    /**
     * Scans {@code syncDir} for all {@code .md} files, reads their {@code title} and {@code description}
     * frontmatter fields, and writes a grouped {@code index.md}. The description field is critical —
     * it's what lets the agent navigate intelligently without reading every file.
     *
     * @param syncDir the root directory of the local knowledge base
     * @param owner   GitHub repo owner (used in the index header)
     * @param repo    GitHub repo name (used in the index header)
     */
    public void generateIndex(Path syncDir, String owner, String repo) throws IOException {
        List<Path> mdFiles = Files.walk(syncDir)
                .filter(p -> p.toString().endsWith(".md"))
                .filter(p -> !p.getFileName().toString().startsWith("."))
                .filter(p -> !syncDir.relativize(p).toString().replace('\\', '/').equals("index.md"))
                .sorted()
                .toList();

        // Group by top-level directory; files at root go in "root" section
        Map<String, List<IndexEntry>> bySection = new LinkedHashMap<>();
        for (Path p : mdFiles) {
            String rel = syncDir.relativize(p).toString().replace('\\', '/');
            String section = rel.contains("/") ? rel.substring(0, rel.indexOf('/')) : "root";
            String title = extractTitle(p);
            String description = extractDescription(p);
            bySection.computeIfAbsent(section, k -> new ArrayList<>())
                     .add(new IndexEntry(title, rel, description));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("""
                ---
                type: index
                title: %s/%s Knowledge Base
                source: https://github.com/%s/%s
                description: Auto-generated OKF index synced from GitHub. Agents read this first to navigate to relevant knowledge files.
                ---

                # %s/%s Knowledge Base

                > Auto-synced from [github.com/%s/%s](https://github.com/%s/%s)
                > Each entry links to an OKF knowledge document — read the description to navigate intelligently.

                """.formatted(owner, repo, owner, repo, owner, repo, owner, repo, owner, repo));

        for (Map.Entry<String, List<IndexEntry>> entry : bySection.entrySet()) {
            String section = entry.getKey().equals("root") ? "Root" : entry.getKey();
            sb.append("## ").append(section).append("\n");
            for (IndexEntry e : entry.getValue()) {
                sb.append("- [").append(e.title()).append("](").append(e.path()).append(")");
                if (!e.description().isEmpty()) {
                    sb.append(" — ").append(e.description());
                }
                sb.append("\n");
            }
            sb.append("\n");
        }

        Files.writeString(syncDir.resolve("index.md"), sb.toString());
        log.info("Index generated: {} sections, {} knowledge files", bySection.size(), mdFiles.size());
    }

    // Reads `title` from frontmatter; falls back to filename
    private String extractTitle(Path p) {
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (content.startsWith("---")) {
                int end = content.indexOf("---", 3);
                if (end > 0) {
                    for (String line : content.substring(4, end).split("\n")) {
                        if (line.startsWith("title:")) {
                            return line.substring(6).trim().replaceAll("^\"|\"$", "");
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return p.getFileName().toString().replaceAll("\\.md$", "").replace('-', ' ').replace('_', ' ');
    }

    // Reads `description` from frontmatter — this is what makes the index navigable by the agent
    private String extractDescription(Path p) {
        try {
            String content = Files.readString(p, StandardCharsets.UTF_8);
            if (content.startsWith("---")) {
                int end = content.indexOf("---", 3);
                if (end > 0) {
                    for (String line : content.substring(4, end).split("\n")) {
                        if (line.startsWith("description:")) {
                            return line.substring(12).trim().replaceAll("^\"|\"$", "");
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        return "";
    }

    private record IndexEntry(String title, String path, String description) {}
}
