# llm-OKF — Open Knowledge Format

A Spring Boot + Spring AI application demonstrating **OKF (Open Knowledge Format)** as a replacement for RAG.

---

## What is OKF?

OKF is a structured knowledge base pattern where knowledge is stored in **markdown files with YAML frontmatter** and navigated by an **AI agent reading an index** rather than querying a vector database.

### RAG vs OKF

| Aspect | RAG | OKF |
|--------|-----|-----|
| Storage | Vector database (Postgres/Redis/Pinecone) | Plain markdown files |
| Retrieval | Similarity search over embeddings | Agent reads index, follows links |
| Chunking | Splits documents into 500-token chunks | Full files preserved |
| Infrastructure | Embedding model + vector DB + re-indexing | Zero — just a filesystem |
| Accuracy | Fuzzy — retrieves by distance, not logic | Deterministic — agent navigates |
| Update | Re-embed on every change | Edit the markdown file |

### When OKF Wins
- Personal or team knowledge bases (playbooks, runbooks, concepts)
- When you control the knowledge structure
- When accuracy > recall
- When you want zero infrastructure

### When RAG Wins
- Millions of documents exceeding any context window
- Unstructured, uncontrolled document sources
- Real-time search over dynamic content

---

## Architecture

```
User Question
     │
     ▼
OkfController  (POST /api/v1/okf/chat)
     │
     ▼
OkfChatService
  ├── Phase 1: OkfNavigator.loadIndex()
  │     └── reads classpath:okf/index.md
  ├── Phase 2: OkfNavigator.findRelevantFiles(question, index)
  │     └── LLM selects relevant file paths from the index
  ├── Phase 3: OkfNavigator.loadFiles(paths)
  │     └── reads each full markdown file
  └── Phase 4: ChatClient.prompt()
        ├── .system("KNOWLEDGE BASE:\n" + fullFileContents)
        └── .user(question)
             └── LLM answers with complete, in-context knowledge
```

No vector store. No embedding model. No chunking. The agent reads the index (small, always fits in context) and then reads the full relevant files.

---

## OKF Knowledge Base Structure

```
src/main/resources/okf/
├── index.md                          ← Master navigation file (agents read this first)
├── concepts/
│   ├── spring-ai-overview.md         ← Spring AI framework, ChatClient, advisors
│   ├── okf-vs-rag.md                 ← Why OKF replaces RAG
│   ├── local-llm-setup.md            ← Ollama, ComfyUI, ROCm on AMD hardware
│   └── prompt-engineering.md         ← System prompts, temperature, structured output
├── playbooks/
│   ├── how-to-query.md               ← Step-by-step OKF navigation pattern
│   └── pdf-to-okf.md                 ← How to convert PDFs into OKF knowledge files
└── references/
    ├── spring-ai-reference.md         ← Spring AI key classes and APIs
    └── ollama-models.md               ← Available Ollama models, sizes, use cases
```

Each file has a YAML frontmatter block:

```markdown
---
type: concept | playbook | reference
title: Human-readable title
tags: [tag1, tag2]
related: [other/files.md]
---

# Content here...
```

---

## Prerequisites

- Java 25
- Maven 3.9+
- Ollama running at `http://localhost:11434` with `llama3.1:8b` pulled

```bash
# Pull the model if not already present
ollama pull llama3.1:8b
```

---

## Running

```bash
cd /home/himansu/projects/llm-OKF
./mvnw spring-boot:run
```

Or with custom settings:

```bash
OLLAMA_MODEL=qwen3:8b SERVER_PORT=8091 ./mvnw spring-boot:run
```

The server starts on **port 8090** by default.

---

## API Endpoints

### Chat — Blocking

```http
POST /api/v1/okf/chat
Content-Type: application/json

{
  "question": "What is OKF and how does it differ from RAG?"
}
```

Response:
```json
{
  "answer": "OKF (Open Knowledge Format) differs from RAG in several key ways...",
  "sourcesUsed": ["concepts/okf-vs-rag.md"],
  "filesLoaded": 1
}
```

### Chat — Streaming (SSE)

```http
POST /api/v1/okf/chat/stream
Content-Type: application/json
Accept: text/event-stream

{
  "question": "Explain the OKF navigation pattern step by step"
}
```

Returns a stream of `text/event-stream` tokens.

### Knowledge Base Inspection

```http
GET /api/v1/okf/index        # Returns the raw index.md
GET /api/v1/okf/files        # Returns all OKF files with parsed frontmatter
```

### PDF Ingestion

```http
POST /api/v1/okf/ingest/pdf
Content-Type: multipart/form-data

file=<pdf file>
```

Extracts text from the PDF using Spring AI's `PagePdfDocumentReader`, wraps it in OKF frontmatter, and saves it as a reference file.

### Infrastructure

```http
GET /actuator/health         # Health check
GET /swagger-ui.html         # Swagger UI
GET /api-docs                # OpenAPI JSON
```

---

## Configuration

All settings in `src/main/resources/application.yml` with environment variable overrides:

| Property | Env Var | Default |
|----------|---------|---------|
| Server port | `SERVER_PORT` | `8090` |
| Ollama base URL | `OLLAMA_BASE_URL` | `http://localhost:11434` |
| Ollama model | `OLLAMA_MODEL` | `llama3.1:8b` |
| Knowledge base path | `OKF_KB_PATH` | `classpath:okf` |
| Max files per query | `OKF_MAX_FILES` | `5` |

---

## Adding Knowledge

1. Create a markdown file in `src/main/resources/okf/<category>/`:

```markdown
---
type: concept
title: My New Topic
tags: [tag1, tag2]
related: [concepts/spring-ai-overview.md]
---

# My New Topic

Content here...
```

2. Add a line to `src/main/resources/okf/index.md` in the appropriate section:

```markdown
- [My New Topic](concepts/my-new-topic.md) — brief description
```

No re-indexing. No re-embedding. Just edit and restart.

---

## Package Structure

```
com.llm.okf
├── config/
│   ├── ChatClientConfig.java       ← ChatClient bean + EnableConfigurationProperties
│   └── OkfProperties.java          ← @ConfigurationProperties(prefix = "app.okf")
├── model/
│   ├── ChatRequest.java            ← DTO with @NotBlank/@Size validation
│   ├── ChatResponse.java           ← answer + sourcesUsed + filesLoaded
│   ├── IngestResponse.java         ← PDF ingestion result
│   └── OkfFile.java                ← Parsed OKF file with frontmatter fields
├── navigator/
│   └── OkfNavigator.java           ← Loads index, navigates via LLM, reads files
├── service/
│   ├── OkfChatService.java         ← Orchestrates navigate → load → answer
│   └── OkfIngestionService.java    ← PDF → OKF markdown conversion
├── controller/
│   └── OkfController.java          ← REST endpoints, Swagger annotations
└── web/
    ├── ApiError.java               ← Structured error response record
    └── GlobalExceptionHandler.java ← @RestControllerAdvice
```
