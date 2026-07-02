# Requirement Spec — `okf-llm-models` Module

> Status: **APPROVED — implemented**
> Author: Claude (from requirements given by Himansu)
> Date: 2026-07-02
>
> Review decisions: (1) Pattern B consumed via REST resolve endpoint only; (2) MongoDB auth
> okf/okf; (3) vanished HF models are kept; (4) size parsed from model name — Option A, may be
> upgraded later; (5) SPEC.md amended with `type: query` (§4.5). Initial scale reduced to
> **50 models** for review — raise `OKF_LLM_MIN_MODELS` to 500 after sign-off.

---

## 1. Goal

Add a third Maven module `okf-llm-models` to the `llm-OKF` multi-module project. It maintains a
catalog of LLM models synced from Hugging Face into MongoDB, and exposes that catalog as OKF
knowledge files using **two distinct patterns**:

- **Pattern A — Materialized OKF**: the OKF file *contains* the model data (like a materialized
  view — data is copied out of MongoDB into the markdown body at generation time).
- **Pattern B — Query OKF**: the OKF file contains *only a MongoDB query definition*, not the
  data (like a plain view — the data is fetched live from MongoDB when the file is consumed).

Minimum **500 OKF files** must exist in the materialized set, one per model.

---

## 2. Architecture Overview

```
                 ┌────────────────────────────────────────────────────┐
                 │                    okf-llm-models                   │
                 │                                                    │
  Hugging Face  ─┼─► HuggingFaceClient ─► HuggingFaceSyncService ──┐  │
  /api/models    │        (REST, paginated)                        │  │
                 │                                                 ▼  │
                 │                                          MongoDB   │
                 │                                       llm_models   │
                 │                                        collection  │
                 │                                             │      │
                 │       ┌─────────────────────────────────────┤      │
                 │       ▼ Pattern A                 Pattern B ▼      │
                 │  OkfMaterializer                OkfQueryFileWriter │
                 │  (writes data files)            (writes query files│
                 │       │                          once / on change) │
                 │       │                                 │          │
                 │       │              OkfQueryResolver ◄─┘          │
                 │       │              (executes query at read time) │
                 └───────┼─────────────────────────────────┼──────────┘
                         ▼                                 ▼
        /home/himansu/projects/okf/llm-models/   /home/himansu/projects/okf/llm-models-live/
              (≥500 materialized .md files)          (small set of query .md files)
```

- Library module (regular jar, no `main` class) — same pattern as `okf-wiki`.
- `okf-chat` (the runnable Spring Boot app) adds it as a Maven dependency; components are
  auto-scanned (package `com.llm.okf.models`).
- Scheduling uses the existing ShedLock JDBC provider (Postgres) — no new lock infrastructure.
- Model data itself lives in **MongoDB** (new infrastructure — added to docker-compose).

---

## 3. Data Source — Hugging Face Sync (Scheduler)

### 3.1 API

- `GET https://huggingface.co/api/models?limit=<page-size>&sort=downloads&direction=-1`
- Paginated via the `Link: <...>; rel="next"` response header; keep fetching pages until at
  least `min-models` (default **50** for the review phase; target 500) models are collected.
- No API token required for public model listings (optional `HF_TOKEN` supported for higher
  rate limits).

### 3.2 Scheduler

Same conventions as `OkfSyncScheduler` in `okf-wiki`:

| Aspect            | Value                                                                                                       |
|-------------------|-------------------------------------------------------------------------------------------------------------|
| Startup sync      | `@Async` on `ApplicationReadyEvent`, gated by `sync-on-startup`                                             |
| Periodic sync     | `@Scheduled` fixed delay, default **24 h**                                                                  |
| Lock              | `@SchedulerLock(name = "okf-hf-models-sync", lockAtMostFor = "PT2H", lockAtLeastFor = "PT5M")`              |
| Flow              | fetch from HF → upsert MongoDB → regenerate Pattern A files → regenerate Pattern B files → write `index.md` |

### 3.3 MongoDB Document (`llm_models` collection)

| Field            | Type              | Source / Derivation                                                                |
|------------------|-------------------|------------------------------------------------------------------------------------|
| `_id`            | String            | HF model id, e.g. `meta-llama/Llama-3.1-8B-Instruct`                               |
| `name`           | String            | id without author prefix                                                           |
| `author`         | String            | id author prefix                                                                   |
| `modelType`      | String            | HF `pipeline_tag` (e.g. `text-generation`, `automatic-speech-recognition`)         |
| `libraryName`    | String            | HF `library_name` (transformers, gguf, diffusers…)                                 |
| `tags`           | List\<String>     | HF `tags`                                                                          |
| `license`        | String            | parsed from `license:*` tag                                                        |
| `paramsBillions` | Double (nullable) | parsed from model id (`8B`, `70B`, `135M`…); null if not derivable                 |
| `sizeCategory`   | String            | derived: `small` (<3B), `medium` (3–15B), `large` (15–70B), `xl` (>70B), `unknown` |
| `canRunLocally`  | Boolean           | derived: params ≤ 15B, or `gguf`/`ggml`/`ollama` tag present                       |
| `textGenerative` | Boolean           | derived: pipeline in text-generation / text2text / conversational                  |
| `multimodal`     | Boolean           | derived: pipeline in image-text-to-text, VQA, any-to-any… or `multimodal` tag      |
| `audio`          | Boolean           | derived: pipeline contains audio/speech (ASR, TTS, audio-classification…)          |
| `vision`         | Boolean           | derived: image/video pipelines (classification, detection, text-to-image…)         |
| `embedding`      | Boolean           | derived: feature-extraction / sentence-similarity                                  |
| `goodFor`        | String            | human-readable sentence mapped from pipeline tag                                   |
| `downloads`      | Long              | HF `downloads`                                                                     |
| `likes`          | Long              | HF `likes`                                                                         |
| `createdAt`      | Instant           | HF `createdAt`                                                                     |
| `lastModified`   | Instant           | HF `lastModified`                                                                  |
| `syncedAt`       | Instant           | time of our sync                                                                   |

Upsert semantics: `_id` is the natural key; re-sync updates in place. Models that disappear from
HF are kept (no delete) — flagged for future cleanup consideration (open question §9.3).

---

## 4. Pattern A — Materialized OKF Files

**One `.md` file per MongoDB document. Data is copied into the file.**

- Output root: `app.okf.llm-models.knowledge-base-path`
  (default `/home/himansu/projects/okf/llm-models`, env `OKF_LLM_KB_PATH`).
- Grouped in subfolders by `modelType`: `text-generation/meta-llama__Llama-3.1-8B-Instruct.md`
  (`/` in model id → `__` in filename).
- Regenerated after every sync — files are disposable, MongoDB is the source of truth
  (materialized-view semantics: refresh = full rewrite of changed docs).
- Deterministic generation from DB fields — **no LLM involved**.
- ≥500 files (matches `min-models`).

### 4.1 File format (OKF spec compliant — `type` required, frontmatter §9)

```markdown
---
type: reference
title: Llama-3.1-8B-Instruct
description: text-generation model by meta-llama — 8B params, runs locally, good for chat & text generation
resource: https://huggingface.co/meta-llama/Llama-3.1-8B-Instruct
tags: [huggingface, llm-model, text-generation, local-capable]
timestamp: 2026-07-02T10:00:00Z
---

# Llama-3.1-8B-Instruct

## Overview
| Property        | Value                        |
|-----------------|------------------------------|
| Author          | meta-llama                   |
| Model type      | text-generation              |
| Library         | transformers                 |
| License         | llama3.1                     |
| Parameters      | 8B                           |
| Size category   | medium                       |
| Downloads       | 12,345,678                   |
| Likes           | 4,321                        |

## Capabilities
- Text-based generative AI: **yes**
- Multimodal: no
- Audio: no
- Vision: no
- Embedding: no

## Local Execution
Can run locally: **yes** (8B params — fits on consumer GPU / via Ollama or llama.cpp)

## Good For
Chat assistants, text generation, instruction following.

## Database Details
| Property    | Value                                   |
|-------------|-----------------------------------------|
| Store       | MongoDB                                 |
| Database    | okf                                     |
| Collection  | llm_models                              |
| Document id | meta-llama/Llama-3.1-8B-Instruct        |
| Synced at   | 2026-07-02T10:00:00Z                    |
```

### 4.2 Index

`index.md` at the root of the folder, grouped by model type section, one
`* [Title](path) - description` line per file (OKF spec §6 format) — same style as the
`okf-wiki` index so an agent can navigate it identically.

---

## 5. Pattern B — Query OKF Files (live view)

**The OKF file contains only the MongoDB query — no data.** The data is fetched at read time.

- Output root: `app.okf.llm-models.query-base-path`
  (default `/home/himansu/projects/okf/llm-models-live`, env `OKF_LLM_QUERY_KB_PATH`).
- Small, curated set of query files (one per useful "view"), generated once by the module
  (rewritten on sync only if the definition changed):

| File                          | Query intent                                    |
|-------------------------------|--------------------------------------------------|
| `all-local-runnable.md`       | `{ canRunLocally: true }`                        |
| `text-generation-models.md`   | `{ modelType: "text-generation" }`               |
| `multimodal-models.md`        | `{ multimodal: true }`                           |
| `audio-models.md`             | `{ audio: true }`                                |
| `embedding-models.md`         | `{ embedding: true }`                            |
| `top-downloaded.md`           | all, sort `downloads` desc, limit 50             |
| `small-models.md`             | `{ sizeCategory: "small" }`                      |
| `model-by-id.md`              | parameterized: `{ _id: "<param:modelId>" }`      |

### 5.1 File format — new OKF `type: query`

```markdown
---
type: query
title: Local-runnable models
description: Live view — all LLM models that can run on local hardware, fetched from MongoDB at read time
tags: [huggingface, llm-model, live-query]
timestamp: 2026-07-02T10:00:00Z
query:
  store: mongodb
  database: okf
  collection: llm_models
  filter: '{ "canRunLocally": true }'
  projection: '{ "name": 1, "modelType": 1, "paramsBillions": 1, "goodFor": 1 }'
  sort: '{ "downloads": -1 }'
  limit: 100
---

# Local-runnable models

This is a live OKF view. The frontmatter `query` block is executed against MongoDB
when this file is resolved — the data below is NOT stored in this file.
```

### 5.2 Query resolution

- New service `OkfQueryResolver`: parses the `query` frontmatter block, executes it via
  `MongoTemplate` (filter/projection/sort/limit only — **no arbitrary commands, no
  `$where`/JavaScript**, collection allow-listed to `llm_models`), renders the result rows as a
  markdown table, and returns the file content with results appended.
- New REST endpoint in `okf-chat`:
  `GET /api/okf/llm-models/resolve?file=<relative-path>` → resolved markdown (query executed,
  results inlined).
- This is the consumption path for Pattern B: agents/chat read the resolved output, never the
  raw file, so the data is always current with MongoDB.

---

## 6. Configuration

`application.yml` additions in `okf-chat`:

```yaml
spring:
  data:
    mongodb:
      uri: ${MONGO_URI:mongodb://okf:okf@localhost:27017/okf?authSource=admin}

app:
  okf:
    llm-models:
      knowledge-base-path: ${OKF_LLM_KB_PATH:/home/himansu/projects/okf/llm-models}
      query-base-path: ${OKF_LLM_QUERY_KB_PATH:/home/himansu/projects/okf/llm-models-live}
      min-models: ${OKF_LLM_MIN_MODELS:50}
      page-size: ${OKF_LLM_PAGE_SIZE:50}
      interval-ms: ${OKF_LLM_SYNC_INTERVAL_MS:86400000}   # 24 h
      enabled: ${OKF_LLM_SYNC_ENABLED:true}
      sync-on-startup: ${OKF_LLM_SYNC_ON_STARTUP:true}
      hf-token: ${HF_TOKEN:}
```

New `@ConfigurationProperties(prefix = "app.okf.llm-models")` record `LlmModelsProperties`
(same style as `OkfProperties`).

---

## 7. Infrastructure

`docker-compose.yml` — new service + volume, same conventions as existing:

```yaml
mongodb:
  image: mongo:7
  container_name: okf-mongodb
  environment:
    MONGO_INITDB_ROOT_USERNAME: okf
    MONGO_INITDB_ROOT_PASSWORD: okf
    MONGO_INITDB_DATABASE: okf
  ports:
    - "27017:27017"
  volumes:
    - okf_mongodata:/data/db
  healthcheck:
    test: ["CMD", "mongosh", "--eval", "db.adminCommand('ping')"]
```

---

## 8. Module Layout

```
okf-llm-models/
├── pom.xml                     (library module; deps: data-mongodb, shedlock-spring, lombok)
└── src/main/java/com/llm/okf/models/
    ├── config/
    │   ├── LlmModelsProperties.java       @ConfigurationProperties
    │   └── HuggingFaceClientConfig.java   dedicated RestClient bean (HF headers)
    ├── client/
    │   └── HuggingFaceClient.java         paginated /api/models fetch
    ├── model/
    │   ├── HfModel.java                   HF API response DTO (record)
    │   ├── LlmModelDoc.java               @Document("llm_models")
    │   └── ModelSyncStatus.java           sync result (counts, errors)
    ├── repository/
    │   └── LlmModelRepository.java        MongoRepository
    ├── service/
    │   ├── ModelEnricher.java             derivations (§3.3): params, capabilities, goodFor
    │   ├── HuggingFaceSyncService.java    HF → MongoDB upsert
    │   ├── OkfMaterializer.java           Pattern A: MongoDB → data files + index.md
    │   ├── OkfQueryFileWriter.java        Pattern B: writes query definition files
    │   └── OkfQueryResolver.java          Pattern B: executes query, renders results
    └── scheduler/
        └── LlmModelsScheduler.java        startup + periodic, ShedLock

okf-chat: new controller endpoint GET /api/okf/llm-models/resolve (Pattern B consumption)
Unit tests: ModelEnricher (capability/params derivation), OkfQueryResolver (query parsing + safety)
```

Parent `pom.xml`: add `<module>okf-llm-models</module>`. `okf-chat/pom.xml`: add dependency.
`README.md`: module tree + description updated.

---

## 9. Open Questions (answer before/while reviewing)

1. **Pattern B consumption**: REST resolve endpoint in `okf-chat` (§5.2) enough, or should the
   chat navigator (`OkfNavigator`) also auto-resolve `type: query` files when it encounters them?
2. **Mongo auth**: docker-compose with user/pass `okf/okf` (mirrors Postgres convention) — OK,
   or no-auth local Mongo?
3. **Deleted models**: models that vanish from HF stay in MongoDB and in the materialized set.
   OK for now?
4. **HF model detail depth**: list API gives type/tags/downloads/likes; exact on-disk size in
   GB needs one extra API call per model (500+ calls per sync). Spec assumes **derive size from
   the parameter count in the model name** instead. OK, or do per-model detail calls?
5. **`type: query` frontmatter**: this extends the OKF spec (currently `concept`/`reference`
   etc.). Should `okf-chat/src/main/resources/SPEC.md` be amended to document it?

---

## 10. Acceptance Criteria

- [ ] `mvn compile` green across all modules; `okf-llm-models` is a plain jar (no fat-jar).
- [ ] `docker compose up` starts MongoDB alongside existing services.
- [ ] On app start, HF sync runs and MongoDB `llm_models` has ≥500 documents.
- [ ] `/home/himansu/projects/okf/llm-models/` contains ≥500 OKF `.md` files + `index.md`,
      grouped by model type, each spec-compliant with the fields in §4.1.
- [ ] `/home/himansu/projects/okf/llm-models-live/` contains the query files of §5 — no data,
      only query definitions.
- [ ] `GET /api/okf/llm-models/resolve?file=all-local-runnable.md` returns markdown with live
      MongoDB results inlined.
- [ ] Re-running sync updates MongoDB and rewrites materialized files (refresh semantics).
- [ ] Existing wiki sync (`okf-wiki`) unaffected.
- [ ] Unit tests for `ModelEnricher` and `OkfQueryResolver` pass.
