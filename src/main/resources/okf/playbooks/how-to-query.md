---
type: playbook
title: How to Query OKF
tags: [query, navigation, agent, retrieval]
related: [concepts/okf-vs-rag.md, concepts/spring-ai-overview.md]
---

# How to Query OKF

## Navigation Pattern

1. Agent receives user question
2. Agent reads index.md (always available, small)
3. Agent identifies relevant file paths from index
4. System loads full content of those files
5. Full content injected as context to LLM
6. LLM answers with complete, in-context knowledge

## Implementation Steps

### Step 1: Load Index
```
OkfNavigator.loadIndex() -> reads index.md, parses frontmatter + body
```

### Step 2: Navigate
```
OkfNavigator.findRelevantFiles(query, index) -> LLM selects files from index
```

### Step 3: Load Files
```
OkfNavigator.loadFiles(filePaths) -> reads each OKF markdown file
```

### Step 4: Generate
```
ChatClient.prompt()
  .system(buildContext(files))
  .user(query)
  .call()
  .content()
```

## Key Principle
The index is small enough to always fit in context. The agent navigates like a librarian — reads the catalog, goes to the right shelf, reads the full book chapter.
