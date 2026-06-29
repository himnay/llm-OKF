---
type: concept
title: Prompt Engineering
tags: [prompts, system-prompt, temperature, structured-output, llm]
related: [concepts/spring-ai-overview.md, references/spring-ai-reference.md]
---

# Prompt Engineering

## System Prompts
The system prompt sets the LLM's role and constraints. Always define it explicitly.

### OKF System Prompt Pattern
```
You are a knowledgeable assistant with access to a structured knowledge base.

KNOWLEDGE BASE CONTEXT:
{context}

Answer using ONLY the information from the knowledge base.
Cite which file/section the information comes from when relevant.
If the knowledge base does not contain relevant information, say so clearly.
```

### Key Rules
1. Be explicit about what the model can and cannot do
2. Tell the model exactly where to find the answer (the injected context)
3. Tell the model to admit ignorance when context lacks the answer — prevents hallucination
4. Keep system prompts short enough to leave room for knowledge context + user question

## Temperature
Controls randomness of output. Range: 0.0 (deterministic) to 1.0+ (creative).

| Use Case | Recommended Temperature |
|----------|------------------------|
| Knowledge retrieval / Q&A | 0.1 – 0.3 |
| Code generation | 0.1 – 0.2 |
| Creative writing | 0.7 – 1.0 |
| Brainstorming | 0.8 – 1.2 |

For OKF queries: use 0.3 — low enough to stay factual, high enough to paraphrase naturally.

## Structured Output
Spring AI supports structured output via `BeanOutputConverter`:

```java
BeanOutputConverter<MyRecord> converter = new BeanOutputConverter<>(MyRecord.class);
String format = converter.getFormat();  // JSON schema instruction

String response = chatClient.prompt()
    .user("Extract data. " + format)
    .call()
    .content();

MyRecord result = converter.convert(response);
```

## Navigation Prompt (OKF-specific)
The navigation prompt asks the LLM to select files from the index. Key design choices:
- Ask for JSON array only, no prose — makes parsing reliable
- Limit max files to avoid overloading context
- Provide the full index so the model knows what's available
- Few-shot example in the prompt improves JSON compliance

## Common Mistakes
- Too long a system prompt squeezes out context space
- Not telling the model to cite sources — responses become vague
- Temperature too high on retrieval tasks — model starts confabulating
- Not extracting JSON from surrounding text — parse robustly with `indexOf('[')` and `lastIndexOf(']')`
