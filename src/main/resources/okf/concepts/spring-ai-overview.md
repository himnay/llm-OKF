---
type: concept
title: Spring AI Overview
tags: [spring-ai, llm, java, chatclient]
related: [concepts/okf-vs-rag.md, references/spring-ai-reference.md]
---

# Spring AI Overview

Spring AI is a Spring Framework project for building AI-powered applications in Java.

## Core Components

### ChatClient
Fluent API for interacting with LLMs. Supports advisors for RAG, memory, logging.

### Advisors
Interceptors in the chat pipeline. RetrievalAugmentationAdvisor enables RAG. Custom advisors enable OKF.

### Document Readers
- PagePdfDocumentReader: reads PDFs
- MarkdownDocumentReader: reads markdown files
- TikaDocumentReader: reads any file format

### Models Supported
- Ollama (local): llama3.1, qwen2.5, deepseek-r1, FLUX
- OpenAI, Anthropic, Azure OpenAI (cloud)

## OKF Integration
In OKF mode, Spring AI's ChatClient is used WITHOUT vector store advisors. Instead, the OkfNavigator reads files and injects full content as system context.
