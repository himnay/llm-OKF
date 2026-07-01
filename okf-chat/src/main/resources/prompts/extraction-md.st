Analyze this markdown document and generate ONLY the OKF YAML frontmatter block for it.
The frontmatter will be prepended to the original document body.

File: {gitPath}

Document content:
{preview}

Respond with ONLY the YAML frontmatter block (including the --- delimiters). Nothing else.
Use EXACTLY this format (OKF spec v0.1 §4.1):

---
type: <concept|reference|playbook>
title: <meaningful human-readable title, NOT just the filename>
description: <ONE sentence max 120 chars — what this document covers, used for index navigation>
resource: {sourceUrl}
tags: [github, {repo}]
timestamp: {timestamp}
---
