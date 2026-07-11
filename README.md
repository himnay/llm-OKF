# llm-OKF — Open Knowledge Format

<img src="image/spring-logo.png" alt="logo" width="80"/>

## Table of contents

1. [What is OKF?](#what-is-okf)
2. [Key Points](#key-points)
3. [Why OKF Instead of RAG?](#why-okf-instead-of-rag)
4. [How It Works — Step by Step](#how-it-works--step-by-step)
5. [concepts/](#concepts)
6. [docs/](#docs)
7. [Multi-Module Architecture](#multi-module-architecture)
8. [Prompt Templates](#prompt-templates)
9. [Database Schema](#database-schema)
10. [Prerequisites](#prerequisites)
11. [Running the Application](#running-the-application)
12. [Development](#development)
13. [Configuration Reference](#configuration-reference)
14. [API Endpoints](#api-endpoints)
15. [Knowledge Base on Disk](#knowledge-base-on-disk)
16. [Implementation Notes](#implementation-notes)
17. [References](#references)

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

| Aspect         | RAG                              | OKF                                 |
|----------------|----------------------------------|-------------------------------------|
| Storage        | Vector DB                        | Plain markdown files on disk        |
| Retrieval      | Cosine similarity search         | LLM reads index, selects files      |
| Chunking       | Yes — 500-token chunks           | No — whole files loaded             |
| Infrastructure | Embedding model + vector DB      | Just filesystem + Ollama            |
| Source updates | Re-embed on change               | Auto-sync from GitHub hourly        |
| Readable       | No — vectors are opaque          | Yes — markdown files                |
| Best for       | Large corpora, semantic search   | Structured repos, curated knowledge |

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

## concepts/

* [Singleton Design Pattern](concepts/singleton-pattern.md) - How to implement the Singleton pattern in Java
* [Observer Pattern](concepts/observer-pattern.md) - How the Observer pattern decouples producers from consumers

## docs/

* [Architecture Overview](docs/architecture.md) - Overview of the system architecture and module responsibilities
```

The index is intentionally small — it contains only paths and one-line descriptions, not full file content. Keeping it small is critical because the entire index is sent to the LLM at query time. If the index were large, it would consume most of the LLM's context window before any answer could be formed.

---

### Phase 2: Answering a Question

When you send a question to `POST /api/v1/okf/chat`, the following four steps happen:

**Step 1 — Load index.md**

`OkfNavigator` reads `index.md` from disk. This is the map of the entire knowledge base — all file paths with their one-line descriptions. Because it is small (only metadata, no content), it fits easily in the LLM context. The index is cached in memory for 60 seconds to avoid disk I/O on every query.

**Step 2 — LLM selects relevant files**

The LLM receives the full content of `index.md` and your question. It is asked to identify which files contain information relevant to the query, and it responds with a JSON array of file paths:

```json
["concepts/singleton-pattern.md", "concepts/factory-pattern.md"]
```

The navigation prompt limits the response to at most `maxFilesPerQuery` files (default: 5) and instructs the LLM to select only directly relevant files. Irrelevant files are not loaded — this keeps the final prompt focused. The response is parsed using Jackson `ObjectMapper` for robustness against malformed output.

**Step 3 — Load full file content from disk**

`OkfNavigator.loadFiles()` reads the complete content of each selected file from disk. There is no chunking and no truncation — the entire OKF document is loaded into memory. This is the fundamental principle of OKF: the LLM gets the complete context, not fragments.

**Step 4 — LLM answers using full file content**

`OkfChatService` builds a final prompt that includes all the loaded file content (with title and tags from frontmatter) and your original question. The LLM produces an answer grounded in the exact knowledge from those files. The HTTP response includes the answer text plus the list of source files that were used.

---

## Multi-Module Architecture

The project is structured as a **multi-module Maven project**. Each data source has its own module, making it easy to add new knowledge sources (Confluence, Notion, JIRA, local files, S3) without modifying existing code.

```
llm-OKF/                    ← Parent aggregator (packaging=pom)
├── okf-llm-models/         ← Data source module: Hugging Face model catalog via MongoDB
├── okf-mcp/                ← MCP tools: expose the model catalog to agents + web enrichment
├── okf-wiki/               ← Data source module: syncs from GitHub
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
    └── src/main/
        ├── java/com/llm/okf/
        │   ├── LlmOkfApplication.java        Main class with @EnableAsync @EnableScheduling
        │   ├── config/
        │   │   └── ChatClientConfig.java     Spring AI ChatClient beans + ObjectMapper bean
        │   ├── controller/
        │   │   └── OkfController.java        REST endpoints (chat, stream, index, files, sync)
        │   ├── exception/
        │   │   ├── ApiError.java             Structured error response
        │   │   └── GlobalExceptionHandler.java  @RestControllerAdvice for global error handling
        │   ├── model/
        │   │   ├── ChatRequest.java          Input DTO: { question: string }
        │   │   ├── ChatResponse.java         Output DTO: answer + sourcesUsed + filesLoaded
        │   │   └── OkfFile.java              Parsed OKF file with frontmatter fields and body
        │   ├── navigator/
        │   │   └── OkfNavigator.java         Reads index, navigates via LLM, loads files
        │   └── service/
        │       └── OkfChatService.java       Orchestrates navigate → load → answer
        └── resources/
            └── prompts/                      Spring AI StringTemplate prompt files
                ├── navigation.st             Navigation LLM prompt (index + query → file paths)
                ├── system.st                 Chat system prompt (context → answer)
                ├── extraction-md.st          Markdown frontmatter generation prompt
                └── extraction-code.st        Code-to-knowledge document generation prompt
```

**`okf-wiki`** is a plain library module. It produces a regular `.jar`, not a fat jar. It has no `main` class. It handles everything related to the GitHub data source: API calls, SHA tracking, scheduling, and OKF document generation.

**`okf-llm-models`** is a plain library module (regular `.jar`, no `main` class). A scheduler syncs the most-downloaded LLM models from the Hugging Face Hub into MongoDB (`llm_models` collection), then exposes the catalog as OKF files in two patterns: **materialized** files that embed the data (one file per model under `OKF_LLM_KB_PATH`, refreshed each sync), and **query** files (`type: query`) that contain only a MongoDB query and are resolved live via `GET /api/v1/okf/llm-models/resolve?file=<name>`. See `okf-llm-models-REQUIREMENTS.md` and SPEC.md §4.5.

**`okf-mcp`** is a plain library module exposing the model catalog as **MCP tools** (Spring AI MCP server, SSE endpoint `/sse`): `searchModels` (keyword search), `getModelKnowledge` (full OKF document), and `enrichModelDetails` — fetches the model's card from huggingface.co and has the local LLM write a 1-2 page profile (description, comparisons, speed, minimum local configuration), cached in MongoDB `llm_model_details`. The same tools are registered on the chat client, so `/chat` answers model questions from live catalog data. The enrichment system prompt lives in `okf-mcp/src/main/resources/prompts/enrichment.st`.

**`okf-chat`** is the runnable Spring Boot application. It depends on `okf-wiki`, `okf-llm-models`, and `okf-mcp` and adds the HTTP layer. The data-source modules are just Maven dependencies from `okf-chat`'s perspective.

### How to Add a New Data Source Module

If you want to sync knowledge from Confluence, for example:

1. Create a new Maven module directory: `okf-confluence/`
2. Add `<module>okf-confluence</module>` to the parent `pom.xml`
3. Implement a service that fetches pages from the Confluence API and writes OKF markdown files to the configured `knowledgeBasePath`
4. Implement a scheduler that runs the sync periodically
5. Add `<dependency>okf-confluence</dependency>` in `okf-chat/pom.xml`

The navigator and chat service do not need to change at all — they read from `knowledgeBasePath` on disk, regardless of where the files came from. GitHub files, Confluence files, and future sources all live side by side in the same knowledge base directory.

---

## Prompt Templates

All LLM prompts are stored as **Spring AI StringTemplate (`.st`) files** under `okf-chat/src/main/resources/prompts/`. Variables use `{variableName}` syntax and are injected at runtime via `PromptTemplate.render(Map.of(...))`. `PromptTemplate` instances are constructed once at startup and reused across requests.

| File                 | Used by                              | Variables                                                        |
|----------------------|--------------------------------------|------------------------------------------------------------------|
| `navigation.st`      | `OkfNavigator.findRelevantFiles()`   | `index`, `query`, `maxFilesPerQuery`                             |
| `system.st`          | `OkfChatService` (chat + stream)     | `context`                                                        |
| `extraction-md.st`   | `GitHubSyncService` — markdown files | `gitPath`, `preview`, `sourceUrl`, `repo`, `timestamp`           |
| `extraction-code.st` | `GitHubSyncService` — code files     | `gitPath`, `sourceUrl`, `lang`, `truncated`, `repo`, `timestamp` |

To change how the LLM selects files, how it answers questions, or how it generates OKF documents — edit the `.st` files directly. No Java recompile needed with DevTools active.

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
- Ollama running locally with at least one model pulled

```bash
# Pull the default chat/navigation model
ollama pull llama4:scout

# Pull the extraction model (used for converting source files to OKF docs)
ollama pull qwen3.6:27b

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
# Build the entire project (compiles okf-wiki then okf-chat)
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
export OLLAMA_MODEL=llama4:scout            # optional, this is the default
mvn -pl okf-chat spring-boot:run
```

On startup the application will:
1. Run Flyway migrations to create `okf_sync_state` and `shedlock` tables
2. Start the GitHub sync **in the background** (non-blocking — the app reaches `ACCEPTING_TRAFFIC` immediately)
3. Listen on port **8090**

Subsequent syncs run automatically every hour.

---

## Development

### Hot Reload with Spring DevTools

`spring-boot-devtools` is included in `okf-chat` (optional scope — not packaged in the fat jar). While running via `mvn spring-boot:run`, any class or resource change triggers an automatic restart. In IntelliJ, enable **Build project automatically** (`Settings → Build, Execution, Deployment → Compiler`) and optionally enable registry key `compiler.automake.allow.when.app.running`.

Prompt templates (`.st` files) under `src/main/resources/prompts/` are classpath resources — DevTools detects changes to them and restarts the context. This means you can tune prompts and see results without a manual restart.

### Running Tests

```bash
# Full test suite
mvn test

# Single module
mvn -pl okf-chat test
```

The `LlmOkfApplicationTests` context load test (`@SpringBootTest`) verifies the full Spring context assembles correctly, including all `ChatClient` beans, prompt template loading, and `ObjectMapper` wiring.

---

## Configuration Reference

All settings have sensible defaults. The only required variable is `GITHUB_REPO_URL`.

| Environment Variable       | Default                                                  | Description                                                       |
|----------------------------|----------------------------------------------------------|-------------------------------------------------------------------|
| `GITHUB_REPO_URL`          | *(required)*                                             | Full URL of the GitHub repo to sync                               |
| `GITHUB_TOKEN`             | *(empty)*                                                | Personal access token — required for private repos                |
| `SERVER_PORT`              | `8090`                                                   | HTTP port                                                         |
| `OLLAMA_BASE_URL`          | `http://localhost:11434`                                 | Ollama server URL                                                 |
| `OLLAMA_MODEL`             | `gemma4:12b`                                             | Primary chat model — answers user questions (tool-capable)        |
| `OLLAMA_CTX`               | `4096`                                                   | Context window in tokens for the primary chat model               |
| `OKF_NAV_MODEL`            | `gemma4:12b`                                             | Navigation model — fast small model that selects files from index |
| `OKF_EXTRACTION_MODEL`     | `qwen3.6:27b`                                            | Extraction model — quality model that converts files to OKF docs  |
| `OKF_KB_PATH`              | `/home/himansu/projects/okf/wiki`                        | Directory where OKF files are stored on disk                      |
| `OKF_MAX_FILES`            | `5`                                                      | Max files loaded per query (navigation step)                      |
| `OKF_SYNC_INTERVAL_MS`     | `3600000`                                                | Sync frequency in ms (default 1 hour)                             |
| `OKF_SYNC_ENABLED`         | `true`                                                   | Set to `false` to disable the scheduler                           |
| `OKF_SYNC_ON_STARTUP`      | `true`                                                   | Sync immediately on startup                                       |
| `OKF_LLM_SUMMARIZE`        | `true`                                                   | Use LLM to generate knowledge docs; `false` wraps raw content     |
| `DB_URL`                   | `jdbc:postgresql://localhost:5432/okf`                   | PostgreSQL JDBC URL                                               |
| `DB_USER`                  | `okf`                                                    | Database username                                                 |
| `DB_PASSWORD`              | `okf`                                                    | Database password                                                 |
| `REDIS_HOST`               | `localhost`                                              | Redis host                                                        |
| `REDIS_PORT`               | `6379`                                                   | Redis port                                                        |
| `MONGO_URI`                | `mongodb://okf:okf@localhost:27017/okf?authSource=admin` | MongoDB connection (LLM model catalog + enrichment cache)         |
| `OKF_LLM_KB_PATH`          | `/home/himansu/projects/okf/llm-models`                  | Materialized (Pattern A) OKF files for the model catalog          |
| `OKF_LLM_QUERY_KB_PATH`    | `/home/himansu/projects/okf/llm-models-live`             | Query-view (Pattern B) OKF files                                  |
| `OKF_LLM_MIN_MODELS`       | `100000`                                                 | Upper cap on synced models (quality gates usually stop earlier)   |
| `OKF_LLM_PAGE_SIZE`        | `100`                                                    | Models per Hugging Face API page                                  |
| `OKF_LLM_SORT`             | `downloads`                                              | Feed order: `downloads`, `likes`, or `trendingScore`              |
| `OKF_LLM_MIN_LIKES`        | `50`                                                     | Quality gate — skip models with fewer community likes             |
| `OKF_LLM_MIN_DOWNLOADS`    | `10000`                                                  | Quality gate — skip models with fewer downloads                   |
| `OKF_LLM_SYNC_INTERVAL_MS` | `86400000`                                               | Catalog sync frequency in ms (default 24 h)                       |
| `OKF_LLM_SYNC_ENABLED`     | `true`                                                   | Set to `false` to disable the catalog scheduler                   |
| `OKF_LLM_SYNC_ON_STARTUP`  | `true`                                                   | Sync the catalog immediately on startup                           |
| `HF_TOKEN`                 | *(empty)*                                                | Hugging Face token — higher rate limits + gated model cards       |
| `OKF_MCP_ENRICH_MODEL`     | `gemma4:12b`                                             | Model that writes web-enriched profiles                           |
| `OKF_MCP_CARD_MAX_CHARS`   | `12000`                                                  | Max model-card characters fed to the enrichment LLM               |
| `OKF_MCP_CACHE_TTL_HOURS`  | `168`                                                    | Enriched profile cache TTL (default 7 days)                       |

### Three LLM Roles

The application uses **three separate LLM clients**, each tuned for its task:

| Role       | Bean                   | Model env var          | Temperature  | Purpose                                                             |
|------------|------------------------|------------------------|--------------|---------------------------------------------------------------------|
| Chat       | `chatClient` (primary) | `OLLAMA_MODEL`         | 0.3          | Answers user questions with full file context + model-catalog tools |
| Navigation | `navigationChatClient` | `OKF_NAV_MODEL`        | 0.0          | Selects relevant files from index — deterministic                   |
| Extraction | `extractionChatClient` | `OKF_EXTRACTION_MODEL` | 0.2          | Converts source files into OKF knowledge docs at sync time          |
| Enrichment | `enrichmentChatClient` | `OKF_MCP_ENRICH_MODEL` | 0.3          | Writes 1-2 page model profiles from Hugging Face cards              |

Using separate models lets you balance speed and quality: navigation runs on a fast small model (low latency per query), extraction runs on a quality model (runs once per file at sync time, quality matters more than speed).

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

Returns a JSON array of all OKF files with their frontmatter metadata (title, type, tags, related). Useful for inspecting what the knowledge base contains without reading individual files.

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

Returns `204 No Content` if no sync has run since startup.

### LLM Model Catalog Endpoints

The Hugging Face model catalog (module `okf-llm-models` + `okf-mcp`) adds its own endpoint group:

```http
POST /api/v1/okf/llm-models/chat          # Catalog-only chat — LLM answers exclusively from the
                                          # HF catalog via tools (search → markdown table); no
                                          # wiki context, one LLM round faster than /okf/chat
POST /api/v1/okf/llm-models/sync          # Manual catalog sync: HF → MongoDB → OKF files
GET  /api/v1/okf/llm-models/sync/status   # Last catalog sync result (204 if none yet)
GET  /api/v1/okf/llm-models/resolve?file=all-local-runnable.md
                                          # Resolve a Pattern B query OKF file — executes its
                                          # MongoDB query, returns markdown with live results
```

Catalog chat example (add `Accept: text/markdown` for a readable markdown response):

```http
POST /api/v1/okf/llm-models/chat
Content-Type: application/json

{ "question": "list the top 10 text-generation models that can run locally" }
```

For plain model listings prefer the `resolve` endpoint — it executes the MongoDB query directly
and returns instantly with no LLM call.

### MCP Server

The same model-catalog tools are exposed over the Model Context Protocol so external agents
(Claude Code, IDEs, other apps) can use the knowledge base directly:

```http
GET  /sse            # MCP SSE handshake — returns the session message endpoint
POST /mcp/message    # JSON-RPC messages (initialize, tools/list, tools/call)
```

Tools: `searchModels`, `getModelKnowledge`, `enrichModelDetails`. Example Claude Code registration:

```bash
claude mcp add okf --transport sse http://localhost:8090/sse
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

All knowledge files are stored under `OKF_KB_PATH` (default: `/home/himansu/projects/okf/wiki`):

```
/home/himansu/projects/okf/wiki/
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

## Implementation Notes

### Frontmatter Parsing

YAML frontmatter is parsed using **SnakeYAML** (`org.yaml.snakeyaml.Yaml`), which is already on the classpath via Spring Boot. This handles all standard YAML types — strings, lists, nested maps — correctly. The previous hand-rolled parser that split on `:` and `[` has been replaced.

### Index Caching

`OkfNavigator.loadIndex()` caches the content of `index.md` in memory for **60 seconds**. Every query previously read the file from disk on every request. The cache uses a `volatile` field + expiry timestamp — no external cache library required.

### JSON Path Parsing

Navigation LLM responses (a JSON array of file paths) are parsed with **Jackson `ObjectMapper`**. The parser extracts the `[...]` array substring first, tolerating any surrounding explanation text the model may emit. This replaces the previous regex which silently returned empty on malformed responses.

### Skipped File Counter

The sync loop now increments `skipped` **inline** when a file is unchanged (matching SHA + model). The previous implementation had a redundant post-loop stream pass that re-counted unchanged files separately, which made the counter logic split across two places and harder to follow.

---

## References

- OKF concept: https://www.mariehaynes.com/build-an-okf-brain-like-mine/
- Spring AI docs: https://docs.spring.io/spring-ai/reference/
- Ollama: https://ollama.com/
- ShedLock: https://github.com/lukas-krecan/ShedLock
- Spec: https://github.com/GoogleCloudPlatform/knowledge-catalog/blob/main/okf/SPEC.md
