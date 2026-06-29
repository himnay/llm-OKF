---
type: reference
title: Ollama Model Reference
tags: [ollama, models, llm, local-ai]
related: [concepts/local-llm-setup.md]
---

# Ollama Model Reference

## Chat Models

| Model | Size | Best For | Speed (CPU) |
|-------|------|---------|-------------|
| llama3.1:8b | 4.9GB | General chat | Fast ~15-30 tok/s |
| qwen2.5-coder:32b | 19GB | Code generation | Very slow 1-3 tok/s |
| deepseek-r1:7b | ~4GB | Reasoning, math | Fast |
| qwen3:8b | ~5GB | Best 8B chat | Fast |

## Embedding Models (NOT for chat)

| Model | Use |
|-------|-----|
| qwen3-embedding:8b | Vector embeddings for RAG only |

## Key Distinction
Embedding models return float arrays (vectors). Chat models return text. Do NOT use embedding models for chat — you will get garbage output.

## API Format
```bash
# Chat
curl http://localhost:11434/api/chat -d '{"model":"llama3.1:8b","messages":[{"role":"user","content":"hello"}]}'

# List models
curl http://localhost:11434/api/tags
```
