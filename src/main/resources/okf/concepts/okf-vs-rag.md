---
type: concept
title: OKF vs RAG
tags: [okf, rag, comparison, retrieval, vector-db]
related: [concepts/spring-ai-overview.md, playbooks/how-to-query.md]
---

# OKF vs RAG

## RAG Problems
1. Chunking destroys context — 500-token chunks lose surrounding meaning
2. Similarity search is fuzzy — retrieves by embedding distance, not logic
3. Requires vector DB + embedding model — infrastructure overhead
4. Re-embedding needed on every document update
5. Can retrieve wrong chunks confidently

## OKF Advantages
1. No chunking — full files preserved, context intact
2. Deterministic navigation — agent follows index links, not similarity
3. Zero infrastructure — just markdown files
4. Update = edit file, no re-indexing
5. Agent knows exactly which file has the answer

## When RAG Wins
- Corpus too large for context window (millions of docs)
- Unstructured, uncontrolled document sources
- Real-time search over dynamic content

## When OKF Wins
- Personal/team knowledge bases
- Structured processes and playbooks
- When accuracy > recall
- When you control the knowledge structure
