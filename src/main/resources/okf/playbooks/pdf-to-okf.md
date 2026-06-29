---
type: playbook
title: PDF to OKF Conversion
tags: [pdf, ingestion, conversion, okf]
related: [concepts/okf-vs-rag.md, references/spring-ai-reference.md]
---

# PDF to OKF Conversion

## Why Convert PDFs to OKF?

PDF documents have unstructured text. OKF gives them a structured identity via YAML frontmatter
so agents can navigate to them via the index. Once converted, the full text is available without chunking.

## Conversion Steps

### Step 1: Read the PDF
Use Spring AI's `PagePdfDocumentReader` to extract text page by page:

```java
PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
List<Document> pages = reader.get();
String fullText = pages.stream()
    .map(Document::getText)
    .collect(joining("\n\n"));
```

### Step 2: Generate a Slug
Convert the filename to a URL-safe slug:
```java
String slug = filename.toLowerCase().replaceAll("[^a-z0-9]+", "-");
```

### Step 3: Wrap in OKF Frontmatter
```markdown
---
type: reference
title: Original PDF Title
tags: [pdf, ingested, slug]
related: []
---

# Original PDF Title

<full extracted text here>
```

### Step 4: Save as Reference File
Save to the `references/` subdirectory so it appears in `listAllFiles()` and can be referenced
from `index.md`.

```
okf/references/<slug>.md
```

### Step 5: Update index.md
Add a line to the References section of `index.md`:
```markdown
- [Original PDF Title](references/slug.md) — brief description
```

## API Endpoint
POST `/api/v1/okf/ingest/pdf` with `multipart/form-data` containing the PDF file.
The endpoint saves the converted file to a temp directory and returns the output path.

## Limitations
- Scanned PDFs (image-only) produce no text — use an OCR step first
- Tables and complex layouts may not extract cleanly — review the output
- Very large PDFs (1000+ pages) should be split into logical sections manually
- After ingestion, add the new file to `index.md` manually so the navigator knows it exists
