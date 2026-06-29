---
type: reference
title: Spring AI Key Classes
tags: [spring-ai, api, reference, java]
related: [concepts/spring-ai-overview.md]
---

# Spring AI Key Classes Reference

## ChatClient
```java
ChatClient.builder(chatModel)
    .defaultSystem("You are a helpful assistant")
    .build();

// Blocking call
String response = chatClient.prompt()
    .user("Hello")
    .call()
    .content();

// Streaming
Flux<String> stream = chatClient.prompt()
    .user("Hello")
    .stream()
    .content();
```

## Document Readers
```java
// PDF
PagePdfDocumentReader reader = new PagePdfDocumentReader(resource);
List<Document> docs = reader.get();

// Markdown
MarkdownDocumentReader mdReader = new MarkdownDocumentReader(resource);
```

## OkfNavigator Pattern (Custom)
```java
// 1. Load index
String index = loadFile("okf/index.md");

// 2. Ask LLM which files are relevant
List<String> filePaths = navigator.findRelevant(query, index);

// 3. Load those files
String context = filePaths.stream()
    .map(this::loadFile)
    .collect(joining("\n\n---\n\n"));

// 4. Answer with context
chatClient.prompt()
    .system("Use this knowledge:\n" + context)
    .user(query)
    .call()
    .content();
```
