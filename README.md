# llm-OKF — Open Knowledge Format

A **Spring Boot + Spring AI** application that turns any GitHub repository into a queryable knowledge base — without a vector database, without embeddings, and without chunking.

Knowledge is synced from GitHub, converted to structured markdown files using a local Ollama LLM, and stored on disk. When you ask a question, an LLM agent reads a lightweight index to find the right files, then loads those full files and answers from their complete content.

---

## What is OKF?

**OKF (Open Knowledge Format)** is a structured way to store knowledge as plain markdown files with YAML frontmatter. Each file is a self-contained knowledge document that describes one concept, pattern, or topic. The files are stored on disk — no database, no embeddings.

Every OKF file follows this format:

```markdown
---
type: concept
title: Singleton Design Pattern in Java
description: How to implement and use the Singleton pattern to ensure a class has only one instance
source: https://github.com/owner/repo/blob/HEAD/patterns/Singleton.java
tags: [github, java, design-patterns]
related: []
---

# Singleton Design Pattern in Java

The Singleton pattern ensures that a class has only one instance throughout the lifetime
of an application. It is commonly used for shared resources like database connections or
configuration managers.

## Key Points
- Constructor is private so no external code can call `new`
- A static field holds the single instance
- Thread safety requires either `synchronized` or eager initialization
```

The `description` field in the frontmatter is the key to navigation. It is a single sentence that tells the LLM agent what this file is about — before loading the full content.

A special `index.md` file is automatically generated, listing every knowledge file with its path and description. This is the entry point for all queries.

---

## Why OKF Instead of RAG?

**RAG (Retrieval-Augmented Generation)** is the common approach: embed documents as vectors, store them in a vector database, and retrieve the nearest chunks when a query comes in. RAG has significant downsides:

- You need an **embedding model** running separately
- You need a **vector database** (Pinecone, Weaviate, pgvector, etc.)
- Documents are **chunked** into 500-token pieces — context is lost at chunk boundaries
- When a document changes, you must **re-embed** it
- Infrastructure is **complex** to set up and expensive to operate

**OKF is different:**

- **No embedding model** — just your local Ollama instance
- **No vector database** — knowledge lives as plain files on disk
- **No chunking** — whole files are loaded and passed to the LLM
- **Auto-synced** — changes in GitHub are pulled hourly, no re-embedding needed
- **Human-readable** — you can open and edit any knowledge file in a text editor

The trade-off is that OKF relies on the LLM's ability to reason over the index and select relevant files — it works best when files have clear, descriptive `description` frontmatter and when the repository contains conceptual knowledge (not millions of tiny files).

| Aspect         | RAG                              | OKF                                |
|----------------|----------------------------------|------------------------------------|
| Storage        | Vector DB                        | Plain markdown files on disk       |
| Retrieval      | Cosine similarity search         | LLM reads index, selects files     |
| Chunking       | Yes — 500-token chunks           | No — whole files loaded            |
| Infrastructure | Embedding model + vector DB      | Just filesystem + Ollama           |
| Source updates | Re-embed on change               | Auto-sync from GitHub hourly       |
| Readable       | No — vectors are opaque          | Yes — markdown files               |
| Best for       | Large corpora, semantic search   | Structured repos, curated knowledge|

---

## How It Works — Step by Step

### Phase 1: GitHub Sync

The app connects to a GitHub repository using the GitHub REST API and builds a knowledge base on disk. This happens once on startup and then every hour (configurable).

**Step 1 — Fetch the file tree**

The GitHub API endpoint `GET /repos/{owner}/{repo}/git/trees/HEAD?recursive=1` returns every file path and its SHA hash in one response. The SHA is a fingerprint of the file content — if the SHA has not changed since the last sync, the file is skipped entirely. Changed or new files are downloaded.

**Step 2 — Filter out non-knowledge files**

Build and tooling files are excluded because they carry no reusable knowledge:

- Filenames like `pom.xml`, `Dockerfile`, `package.json`, `.gitignore`, `Makefile`, `gradlew`
- Directories like `src/test/`, `.github/`, `node_modules/`, `.idea/`, `.vscode/`
- Binary files: images, archives, compiled artifacts, fonts, audio/video

**Step 3 — Download and convert each changed file**

For every file whose SHA has changed (or that is new), the raw content is fetched from `raw.githubusercontent.com`. Then an OKF document is generated:

- **For code files** (`.java`, `.py`, `.go`, etc.) — the Ollama LLM reads the code and writes a plain-language knowledge document explaining the concept, pattern, or idea in the file. The raw source code is appended at the bottom for reference. The goal is to capture *what* the code teaches, not just *what* the code does.
- **For markdown files** (`.md`) — the LLM generates YAML frontmatter (`title`, `description`, `type`, `tags`) and prepends it to the file. The original markdown body is preserved exactly as written.
- **Fallback** — if the LLM call fails or produces invalid output, the file is wrapped in minimal OKF frontmatter automatically without LLM involvement. The sync never fails because of a single file.

**Step 4 — Persist the SHA immediately**

After each file is written to disk, its SHA is saved to the `okf_sync_state` table in PostgreSQL. This happens **immediately after each individual file** — not at the end of the whole sync. This is the crash recovery design: if the application crashes halfway through a 200-file sync, the next sync will resume from where it stopped rather than reprocessing everything from scratch.

**Step 5 — Clean up deleted files**

Any file that existed in the previous sync but is no longer in the GitHub tree is deleted from disk and removed from the SHA table. This keeps the knowledge base clean when files are renamed or removed from the repository.

**Step 6 — Regenerate index.md**

After all files are processed, `OkfIndexGenerator` walks the knowledge base directory and writes a fresh `index.md`. The index lists every file, grouped by directory, with its path and one-line description:

```markdown
# OKF Knowledge Base Index
Source: https://github.com/owner/repo
Last synced: 2026-06-29T10:30:00Z
Total files: 42

## concepts/

- `concepts/singleton-pattern.md` — How to implement the Singleton pattern in Java
- `concepts/observer-pattern.md` — How the Observer pattern decouples producers from consumers

## docs/

- `docs/architecture.md` — Overview of the system architecture and module responsibilities
```

The index is intentionally small — it contains only paths and one-line descriptions, not full file content. Keeping it small is critical because the entire index is sent to the LLM at query time. If the index were large, it would consume most of the LLM's context window before any answer could be formed.

---

### Phase 2: Answering a Question

When you send a question to `POST /api/v1/okf/chat`, the following four steps happen:

**Step 1 — Load index.md**

`OkfNavigator` reads `index.md` from disk. This is the map of the entire knowledge base — all file paths with their one-line descriptions. Because it is small (only metadata, no content), it fits easily in the LLM context.

**Step 2 — LLM selects relevant files**

The LLM receives the full content of `index.md` and your question. It is asked to identify which files contain information relevant to the query, and it responds with a JSON array of file paths:

```json
["concepts/singleton-pattern.md", "concepts/factory-pattern.md"]
```

The navigation prompt limits the response to at most `maxFilesPerQuery` files (default: 5) and instructs the LLM to select only directly relevant files. Irrelevant files are not loaded — this keeps the final prompt focused.

**Step 3 — Load full file content from disk**

`OkfNavigator.loadFiles()` reads the complete content of each selected file from disk. There is no chunking and no truncation — the entire OKF document is loaded into memory. This is the fundamental principle of OKF: the LLM gets the complete context, not fragments.

**Step 4 — LLM answers using full file content**

`OkfChatService` builds a final prompt that includes all the loaded file content and your original question. The LLM produces an answer grounded in the exact knowledge from those files. The HTTP response includes the answer text plus the list of source files that were used.

---

## Multi-Module Architecture

The project is structured as a **multi-module Maven project**. Each data source has its own module, making it easy to add new knowledge sources (Confluence, Notion, JIRA, local files, S3) without modifying existing code.

```
llm-OKF/                    ← Parent aggregator (packaging=pom)
├── okf-github/             ← Data source module: syncs from GitHub
│   └── src/main/java/com/llm/okf/
│       ├── config/
│       │   ├── OkfProperties.java        @ConfigurationProperties (all app settings)
│       │   ├── SchedulerConfig.java      ShedLock + TaskScheduler beans
│       │   └── WebConfig.java            RestClient bean for GitHub API calls
│       ├── model/
│       │   ├── GitHubTreeItem.java       Single file in GitHub tree response
│       │   ├── GitHubTreeResponse.java   Full GitHub tree API response
│       │   └── SyncStatus.java           Sync result with counts and errors
│       ├── repository/
│       │   └── OkfSyncStateRepository.java  SHA tracking in PostgreSQL
│       ├── scheduler/
│       │   └── OkfSyncScheduler.java     @Scheduled + startup sync on ApplicationReadyEvent
│       └── service/
│           ├── GitHubSyncService.java    GitHub API fetch, OKF generation, SHA tracking
│           └── OkfIndexGenerator.java    Generates index.md from the synced file tree
│
└── okf-chat/               ← Spring Boot runnable application (fat jar)
    └── src/main/java/com/llm/okf/
        ├── LlmOkfApplication.java        Main class with @EnableAsync @EnableScheduling
        ├── config/
        │   └── ChatClientConfig.java     Spring AI ChatClient bean wired to Ollama
        ├── controller/
        │   └── OkfController.java        REST endpoints (chat, stream, index, files, sync)
        ├── model/
        │   ├── ChatRequest.java          Input DTO: { question: string }
        │   ├── ChatResponse.java         Output DTO: answer + sourcesUsed + filesLoaded
        │   └── OkfFile.java              Parsed OKF file with frontmatter fields and body
        ├── navigator/
        │   └── OkfNavigator.java         Reads index, navigates via LLM, loads files
        ├── service/
        │   └── OkfChatService.java       Orchestrates navigate → load → answer
        └── web/
            ├── ApiError.java             Structured error response
            └── GlobalExceptionHandler.java  @RestControllerAdvice for global error handling
```

**`okf-github`** is a plain library module. It produces a regular `.jar`, not a fat jar. It has no `main` class. It handles everything related to the GitHub data source: API calls, SHA tracking, scheduling, and OKF document generation.

**`okf-chat`** is the runnable Spring Boot application. It depends on `okf-github` and adds the HTTP layer. The `okf-github` module is just a Maven dependency from `okf-chat`'s perspective.

### How to Add a New Data Source Module

If you want to sync knowledge from Confluence, for example:

1. Create a new Maven module directory: `okf-confluence/`
2. Add `<module>okf-confluence</module>` to the parent `pom.xml`
3. Implement a service that fetches pages from the Confluence API and writes OKF markdown files to the configured `knowledgeBasePath`
4. Implement a scheduler that runs the sync periodically
5. Add `<dependency>okf-confluence</dependency>` in `okf-chat/pom.xml`

The navigator and chat service do not need to change at all — they read from `knowledgeBasePath` on disk, regardless of where the files came from. GitHub files, Confluence files, and future sources all live side by side in the same knowledge base directory.

---

## Database Schema

Two tables are created automatically by **Flyway** on startup. You do not need to create them manually.

**`okf_sync_state`** — stores the SHA of every synced file to detect which files changed:

```sql
CREATE TABLE okf_sync_state (
    repo_url   TEXT        NOT NULL,
    file_path  TEXT        NOT NULL,
    sha        TEXT        NOT NULL,
    synced_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (repo_url, file_path)
);
```

When syncing, every file's current SHA (from the GitHub tree) is compared against the stored SHA. If they match, the file is skipped. If they differ (or the file is new), the file is downloaded and processed, and the SHA is updated immediately after.

**`shedlock`** — prevents multiple application instances from running the sync simultaneously:

```sql
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMPTZ  NOT NULL,
    locked_at  TIMESTAMPTZ  NOT NULL,
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
);
```

ShedLock acquires a named lock before each scheduled sync and releases it when done. If one instance holds the lock, other instances skip that scheduled run.

---

## Prerequisites

- Java 21 or higher
- Maven 3.9 or higher
- PostgreSQL database (local or Docker)
- Ollama running locally with a model pulled

```bash
# Pull the LLM model
ollama pull llama3.1:8b

# Start PostgreSQL via Docker (one-liner for local dev)
docker run -d --name okf-postgres \
  -e POSTGRES_DB=okf \
  -e POSTGRES_USER=okf \
  -e POSTGRES_PASSWORD=okf \
  -p 5432:5432 \
  postgres:16
```

---

## Running the Application

Build and run the `okf-chat` module from the project root:

```bash
# Build the entire project (compiles okf-github then okf-chat)
mvn clean package -DskipTests

# Run with required environment variables
GITHUB_REPO_URL=https://github.com/your-org/your-repo \
GITHUB_TOKEN=ghp_your_token \
mvn -pl okf-chat spring-boot:run
```

Or export the variables first:

```bash
export GITHUB_REPO_URL=https://github.com/your-org/your-repo
export GITHUB_TOKEN=ghp_your_token          # omit for public repos
export OLLAMA_MODEL=llama3.1:8b             # optional, this is the default
mvn -pl okf-chat spring-boot:run
```

On startup the application will:
1. Run Flyway migrations to create `okf_sync_state` and `shedlock` tables
2. Start the GitHub sync **in the background** (non-blocking — the app reaches `ACCEPTING_TRAFFIC` immediately)
3. Listen on port **8090**

Subsequent syncs run automatically every hour.

---

## Configuration Reference

All settings have sensible defaults. The only required variable is `GITHUB_REPO_URL`.

| Environment Variable    | Default                              | Description                                              |
|-------------------------|--------------------------------------|----------------------------------------------------------|
| `GITHUB_REPO_URL`       | *(required)*                         | Full URL of the GitHub repo to sync                      |
| `GITHUB_TOKEN`          | *(empty)*                            | Personal access token — required for private repos       |
| `SERVER_PORT`           | `8090`                               | HTTP port                                                |
| `OLLAMA_BASE_URL`       | `http://localhost:11434`             | Ollama server URL                                        |
| `OLLAMA_MODEL`          | `llama3.1:8b`                        | Model for both chat and LLM knowledge extraction         |
| `OLLAMA_CTX`            | `32768`                              | Context window in tokens                                 |
| `OKF_KB_PATH`           | `/home/himansu/projects/okf`         | Directory where OKF files are stored on disk             |
| `OKF_MAX_FILES`         | `5`                                  | Max files loaded per query (navigation step)             |
| `OKF_SYNC_INTERVAL_MS`  | `3600000`                            | Sync frequency in ms (default 1 hour)                    |
| `OKF_SYNC_ENABLED`      | `true`                               | Set to `false` to disable the scheduler                  |
| `OKF_SYNC_ON_STARTUP`   | `true`                               | Sync immediately on startup                              |
| `OKF_LLM_SUMMARIZE`     | `true`                               | Use LLM to generate knowledge docs; `false` wraps raw    |
| `DB_URL`                | `jdbc:postgresql://localhost:5432/okf` | PostgreSQL JDBC URL                                    |
| `DB_USER`               | `okf`                                | Database username                                        |
| `DB_PASSWORD`           | `okf`                                | Database password                                        |

---

## API Endpoints

### Ask a Question

Sends a natural language question to the OKF agent. The agent navigates the index, loads the relevant files, and returns a grounded answer.

```http
POST /api/v1/okf/chat
Content-Type: application/json

{
  "question": "What Java patterns are covered in the learning repo?"
}
```

Response:

```json
{
  "answer": "The repository covers the Singleton pattern (ensures one instance per JVM), the Observer pattern (event-driven decoupling), and the Factory pattern (object creation without specifying the concrete class)...",
  "sourcesUsed": [
    "concepts/singleton-pattern.md",
    "concepts/observer-pattern.md"
  ],
  "filesLoaded": 2
}
```

### Ask a Question — Streaming

Same as above but streams the answer as Server-Sent Events, token by token. Use this for a chat-style UI where you want to display the answer as it is being generated.

```http
POST /api/v1/okf/chat/stream
Content-Type: application/json
Accept: text/event-stream

{
  "question": "Summarize the key topics in this repo"
}
```

### View the Knowledge Base Index

Returns the full content of `index.md` — the auto-generated map of all knowledge files with their descriptions.

```http
GET /api/v1/okf/index
```

### List All Knowledge Files

Returns a JSON array of all OKF files with their frontmatter metadata (title, type, tags, description, related). Useful for inspecting what the knowledge base contains without reading individual files.

```http
GET /api/v1/okf/files
```

### Trigger a Manual Sync

Runs a full GitHub sync immediately, outside the normal schedule. Useful when you want to pull in recent changes right away.

```http
POST /api/v1/okf/sync
```

Response:

```json
{
  "status": "SUCCESS",
  "lastSync": "2026-06-29T10:30:00Z",
  "totalFiles": 42,
  "newFiles": 5,
  "updatedFiles": 3,
  "skippedFiles": 34,
  "errors": []
}
```

Possible status values:
- `SUCCESS` — all files processed without errors
- `PARTIAL` — some files failed (listed in `errors`), others succeeded
- `FAILED` — the sync failed before processing any files (e.g., GitHub API unreachable)

### Get Last Sync Status

Returns the result of the most recent sync without triggering a new one.

```http
GET /api/v1/okf/sync/status
```

### Infrastructure Endpoints

```http
GET /actuator/health      # Health and readiness check
GET /actuator/info        # Build info and git commit SHA
GET /actuator/metrics     # JVM and HTTP metrics
GET /swagger-ui.html      # Interactive Swagger UI
GET /api-docs             # OpenAPI JSON specification
```

---

## Knowledge Base on Disk

All knowledge files are stored under `OKF_KB_PATH` (default: `/home/himansu/projects/okf`):

```
/home/himansu/projects/okf/
├── index.md                              ← Auto-generated navigation index
├── README.md                             ← From GitHub root (with OKF frontmatter added)
├── docs/
│   ├── architecture.md                   ← Markdown files: original body + generated frontmatter
│   └── getting-started.md
└── src/
    └── main/
        └── java/
            └── patterns/
                ├── Singleton.java.md     ← Code files: converted to OKF knowledge docs (.md appended)
                └── Observer.java.md
```

Key points about how files are stored:

- **Code files** get `.md` appended — `Singleton.java` on GitHub becomes `Singleton.java.md` in the knowledge base
- **Markdown files** keep their original extension — `README.md` stays `README.md`
- The **directory structure mirrors GitHub** exactly
- Files **deleted from GitHub** are deleted from disk on the next sync
- `index.md` is always **regenerated fresh** and is never synced from GitHub
- The knowledge base directory is plain files — you can browse it with any text editor, search it with `grep`, or version it with git

---

## References

- OKF concept: https://www.mariehaynes.com/build-an-okf-brain-like-mine/
- Spring AI docs: https://docs.spring.io/spring-ai/reference/
- Ollama: https://ollama.com/
- ShedLock: https://github.com/lukas-krecan/ShedLock
