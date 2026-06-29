package com.llm.okf.service;

import com.llm.okf.config.OkfProperties;
import com.llm.okf.model.GitHubTreeItem;
import com.llm.okf.model.GitHubTreeResponse;
import com.llm.okf.model.SyncStatus;
import com.llm.okf.repository.OkfSyncStateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubSyncService {

    private final ChatClient extractionChatClient;
    private final OkfProperties properties;
    private final OkfIndexGenerator indexGenerator;
    private final OkfSyncStateRepository syncStateRepository;
    private final RestClient restClient;

    private static final Set<String> BINARY_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "svg", "ico", "bmp", "webp",
            "zip", "jar", "war", "tar", "gz", "7z", "rar",
            "class", "exe", "bin", "so", "dll", "dylib",
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "mp3", "mp4", "avi", "mov", "wav",
            "woff", "woff2", "ttf", "eot", "otf"
    );

    /** Path segments that identify non-knowledge directories — excluded from sync. */
    private static final List<String> SKIP_PATH_SEGMENTS = List.of(
            "src/test/", "src/it/", "src/intTest/",
            ".github/", ".idea/", ".vscode/",
            "node_modules/", "__pycache__/", ".mvn/"
    );

    /** Build/tooling filenames that contain no reusable knowledge — excluded from sync. */
    private static final Set<String> SKIP_FILENAMES = Set.of(
            "pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts",
            "gradlew", "gradlew.bat", "mvnw", "mvnw.cmd",
            "package.json", "package-lock.json", "yarn.lock", "pnpm-lock.yaml",
            ".gitignore", ".gitattributes", ".editorconfig", ".npmrc", ".nvmrc",
            "Makefile", "Dockerfile", "docker-compose.yml", "docker-compose.yaml",
            "Jenkinsfile", "Procfile"
    );

    private volatile SyncStatus lastStatus;

    /**
     * Fetches the full file tree from GitHub, compares SHAs against the last known state,
     * downloads and generates OKF documents for changed files, and regenerates {@code index.md}.
     *
     * @return sync result with counts of new/updated/skipped files and any per-file errors
     */
    public SyncStatus sync() {
        String githubUrl = properties.sync().githubUrl();
        String[] ownerRepo = parseOwnerRepo(githubUrl);
        String owner = ownerRepo[0], repo = ownerRepo[1];

        log.info("GitHub sync starting: {}/{}", owner, repo);

        List<String> errors = new ArrayList<>();
        int newFiles = 0, updatedFiles = 0, skipped = 0;

        try {
            Path syncDir = Path.of(properties.knowledgeBasePath());
            Files.createDirectories(syncDir);

            GitHubTreeResponse tree = restClient.get()
                    .uri("https://api.github.com/repos/{owner}/{repo}/git/trees/HEAD?recursive=1", owner, repo)
                    .headers(this::applyAuth)
                    .retrieve()
                    .body(GitHubTreeResponse.class);

            if (tree == null || tree.tree() == null) {
                throw new RuntimeException("Null tree response from GitHub API");
            }
            if (tree.truncated()) {
                log.warn("GitHub tree truncated — repo too large, some files may be missing");
            }

            List<GitHubTreeItem> blobs = tree.tree().stream()
                    .filter(i -> "blob".equals(i.type()) && !isBinary(i.path()) && !isSkipped(i.path()))
                    .toList();

            log.info("Processing {} files (skipping binaries) from {}/{}", blobs.size(), owner, repo);

            Map<String, String> existingShas = syncStateRepository.loadShas(githubUrl);
            Map<String, String> currentShas = new LinkedHashMap<>();
            int total = blobs.size();
            int processed = 0;

            for (GitHubTreeItem item : blobs) {
                processed++;
                currentShas.put(item.path(), item.sha());

                Path okfPath = resolveOkfPath(syncDir, item.path());
                boolean exists = Files.exists(okfPath);
                String prevSha = existingShas.get(item.path());

                if (exists && item.sha().equals(prevSha)) {
                    continue;
                }

                log.info("[{}/{}] Syncing: {}", processed, total, item.path());

                try {
                    String raw = restClient.get()
                            .uri("https://raw.githubusercontent.com/{owner}/{repo}/HEAD/{path}",
                                    owner, repo, item.path())
                            .headers(this::applyAuth)
                            .retrieve()
                            .body(String.class);

                    if (raw == null || raw.isBlank()) {
                        skipped++;
                        continue;
                    }

                    Files.createDirectories(okfPath.getParent());
                    String okfContent = generateOkfDocument(item.path(), raw, owner, repo);
                    Files.writeString(okfPath, okfContent, StandardCharsets.UTF_8);

                    // Persist SHA immediately — crash recovery: next sync skips already-processed files
                    syncStateRepository.saveOne(item.path(), item.sha(), githubUrl);
                    if (!exists) newFiles++;
                    else updatedFiles++;

                } catch (Exception e) {
                    log.error("Failed to process {}: {}", item.path(), e.getMessage());
                    errors.add(item.path() + ": " + e.getMessage());
                }
            }

            skipped += (int) blobs.stream().filter(i -> {
                String prevSha = existingShas.get(i.path());
                Path okfPath = resolveOkfPath(syncDir, i.path());
                return Files.exists(okfPath) && i.sha().equals(prevSha);
            }).count();

            // Delete local OKF files for paths removed from GitHub
            int deleted = 0;
            for (String removedPath : existingShas.keySet()) {
                if (!currentShas.containsKey(removedPath)) {
                    Path okfPath = resolveOkfPath(syncDir, removedPath);
                    if (Files.deleteIfExists(okfPath)) {
                        log.info("Deleted orphaned OKF file: {}", removedPath);
                        deleted++;
                    }
                }
            }
            if (deleted > 0) syncStateRepository.deleteAll(
                    existingShas.keySet().stream().filter(p -> !currentShas.containsKey(p)).toList(), githubUrl);

            indexGenerator.generateIndex(syncDir, owner, repo);

            String statusStr = errors.isEmpty() ? "SUCCESS" : "PARTIAL";
            SyncStatus status = new SyncStatus(statusStr, Instant.now(),
                    newFiles + updatedFiles, newFiles, updatedFiles, skipped, errors);
            lastStatus = status;
            log.info("Sync complete — {} new, {} updated, {} unchanged, {} errors",
                    newFiles, updatedFiles, skipped, errors.size());
            return status;

        } catch (Exception e) {
            log.error("Sync failed", e);
            errors.add("Fatal: " + e.getMessage());
            SyncStatus status = new SyncStatus("FAILED", Instant.now(), 0, 0, 0, 0, errors);
            lastStatus = status;
            return status;
        }
    }

    /** Returns the result of the most recent sync, or {@code null} if no sync has run since startup. */
    public SyncStatus getLastStatus() {
        return lastStatus;
    }

    // Generates a proper OKF knowledge document. For new/changed files:
    // - If LLM summarization is on: LLM extracts the concept/knowledge from the file content
    // - If off (or LLM fails): wraps content in minimal OKF frontmatter
    // Preserves existing OKF documents that already have description + title.
    private String generateOkfDocument(String gitPath, String rawContent, String owner, String repo) {
        String sourceUrl = "https://github.com/" + owner + "/" + repo + "/blob/HEAD/" + gitPath;

        if (gitPath.endsWith(".md") && hasCompleteFrontmatter(rawContent)) {
            return rawContent;
        }

        if (properties.sync().useLlmSummarization()) {
            try {
                return generateWithLlm(gitPath, rawContent, sourceUrl, repo);
            } catch (Exception e) {
                log.warn("LLM knowledge extraction failed for {}: {} — falling back to simple wrap", gitPath, e.getMessage());
            }
        }

        return buildSimpleOkf(gitPath, rawContent, sourceUrl, repo);
    }

    private String generateWithLlm(String gitPath, String rawContent, String sourceUrl, String repo) {
        if (gitPath.endsWith(".md")) {
            return generateMdOkf(gitPath, rawContent, sourceUrl, repo);
        }
        return generateCodeOkf(gitPath, rawContent, sourceUrl, repo);
    }

    // For .md files: LLM generates OKF frontmatter only — original body is preserved intact.
    // This keeps the author's original documentation while making it navigable via index descriptions.
    private String generateMdOkf(String gitPath, String rawContent, String sourceUrl, String repo) {
        String preview = rawContent.length() > 2000 ? rawContent.substring(0, 2000) + "\n..." : rawContent;

        String prompt = """
                Analyze this markdown document and generate ONLY the OKF YAML frontmatter block for it.
                The frontmatter will be prepended to the original document body.

                File: %s

                Document content:
                %s

                Respond with ONLY the YAML frontmatter block (including the --- delimiters). Nothing else.
                Use EXACTLY this format:

                ---
                type: <concept|reference|playbook>
                title: <meaningful human-readable title, NOT just the filename>
                description: <ONE sentence max 120 chars — what this document covers, used for index navigation>
                source: %s
                tags: [github, %s]
                related: []
                ---
                """.formatted(gitPath, preview, sourceUrl, repo);

        try {
            String response = extractionChatClient.prompt().user(prompt).call().content().trim();
            String frontmatter = extractFrontmatterBlock(response);
            if (frontmatter != null && hasCompleteFrontmatter(frontmatter)) {
                return frontmatter + "\n\n" + rawContent;
            }
            log.warn("LLM frontmatter for {} invalid — falling back", gitPath);
        } catch (Exception e) {
            log.warn("LLM call failed for {}: {} — falling back", gitPath, e.getMessage());
        }
        return buildSimpleOkf(gitPath, rawContent, sourceUrl, repo);
    }

    // For code files: LLM generates a full OKF knowledge document — explains the concept/pattern
    // in plain language rather than just wrapping raw code.
    private String generateCodeOkf(String gitPath, String rawContent, String sourceUrl, String repo) {
        String lang = detectLang(gitPath);
        String truncated = rawContent.length() > 3000
                ? rawContent.substring(0, 3000) + "\n...[content truncated]"
                : rawContent;

        String prompt = """
                You are an OKF (Open Knowledge Format) knowledge curator.
                Extract and document the KEY KNOWLEDGE from this source file as a structured OKF knowledge document.
                Write for UNDERSTANDING — explain the concept, pattern, or idea in plain language.
                Do NOT just describe what the code does line by line.

                File: %s
                GitHub: %s

                Content:
                ```%s
                %s
                ```

                Respond with ONLY a valid OKF markdown document. Use EXACTLY this structure (preserve the --- delimiters and field names):

                ---
                type: <concept|reference|playbook>
                title: <short human-readable title, e.g. "Singleton Design Pattern in Java">
                description: <ONE sentence max 120 chars — what key knowledge this captures for index navigation>
                source: %s
                tags: [github, %s]
                related: []
                ---

                # <title>

                <2-3 paragraphs explaining the concept, pattern, or knowledge in plain language. Why does it matter? What is the core idea?>

                ## Key Points
                - <key insight 1>
                - <key insight 2>
                - <key insight 3>
                """.formatted(gitPath, sourceUrl, lang, truncated, sourceUrl, repo);

        try {
            String response = extractionChatClient.prompt().user(prompt).call().content();
            if (hasCompleteFrontmatter(response)) {
                return response.trim();
            }
            log.warn("LLM response for {} missing valid OKF frontmatter — falling back", gitPath);
        } catch (Exception e) {
            log.warn("LLM call failed for {}: {} — falling back", gitPath, e.getMessage());
        }
        return buildSimpleOkf(gitPath, rawContent, sourceUrl, repo);
    }

    private String extractFrontmatterBlock(String response) {
        int start = response.indexOf("---");
        if (start < 0) return null;
        int end = response.indexOf("---", start + 3);
        if (end < 0) return null;
        return response.substring(start, end + 3).trim();
    }

    private boolean hasCompleteFrontmatter(String content) {
        if (!content.startsWith("---")) return false;
        int end = content.indexOf("---", 3);
        if (end < 0) return false;
        String fm = content.substring(4, end);
        return fm.contains("title:") && fm.contains("description:");
    }

    private String buildSimpleOkf(String gitPath, String content, String sourceUrl, String repo) {
        String filename = gitPath.substring(gitPath.lastIndexOf('/') + 1);
        String title = filename.replaceAll("\\.[^.]+$", "");
        String lang = detectLang(gitPath);

        if (gitPath.endsWith(".md")) {
            return "---\ntype: concept\ntitle: " + title
                    + "\ndescription: " + title + " — knowledge file from " + repo
                    + "\nsource: " + sourceUrl
                    + "\ntags: [github, " + repo + "]\nrelated: []\n---\n\n" + content;
        }

        return """
                ---
                type: reference
                title: %s
                description: %s — source code reference from %s
                source: %s
                tags: [github, code, %s, %s]
                related: []
                ---

                # %s

                Source: `%s`

                ```%s
                %s
                ```
                """.formatted(title, title, repo, sourceUrl, repo, lang, title, gitPath, lang, content);
    }

    private Path resolveOkfPath(Path syncDir, String gitPath) {
        return gitPath.endsWith(".md")
                ? syncDir.resolve(gitPath)
                : syncDir.resolve(gitPath + ".md");
    }

    private boolean isBinary(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return false;
        return BINARY_EXTENSIONS.contains(path.substring(dot + 1).toLowerCase());
    }

    private boolean isSkipped(String path) {
        String filename = path.substring(path.lastIndexOf('/') + 1);
        if (SKIP_FILENAMES.contains(filename)) return true;
        return SKIP_PATH_SEGMENTS.stream().anyMatch(path::contains);
    }

    private String[] parseOwnerRepo(String url) {
        String path = url.replaceFirst("https?://github\\.com/", "").replaceAll("/$", "");
        String[] parts = path.split("/");
        if (parts.length < 2) throw new IllegalArgumentException("Invalid GitHub URL: " + url);
        return new String[]{parts[0], parts[1]};
    }

    private void applyAuth(org.springframework.http.HttpHeaders headers) {
        String token = properties.sync().githubToken();
        if (token != null && !token.isBlank()) {
            headers.setBearerAuth(token);
        }
    }

    private String detectLang(String path) {
        int dot = path.lastIndexOf('.');
        if (dot < 0) return "text";
        return switch (path.substring(dot + 1).toLowerCase()) {
            case "java" -> "java";
            case "py" -> "python";
            case "js" -> "javascript";
            case "ts" -> "typescript";
            case "go" -> "go";
            case "rs" -> "rust";
            case "sh", "bash", "fish" -> "bash";
            case "yml", "yaml" -> "yaml";
            case "json" -> "json";
            case "xml" -> "xml";
            case "sql" -> "sql";
            case "kt" -> "kotlin";
            case "scala" -> "scala";
            case "rb" -> "ruby";
            case "php" -> "php";
            case "c", "h" -> "c";
            case "cpp", "cc" -> "cpp";
            case "cs" -> "csharp";
            case "html" -> "html";
            case "css" -> "css";
            case "properties" -> "properties";
            case "toml" -> "toml";
            case "gradle" -> "groovy";
            default -> "text";
        };
    }

}
