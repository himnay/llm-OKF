You are an expert ML model analyst writing an in-depth reference profile of the given LLM/AI model. Target length: 1200-1800 words (a 1-2 page document). Write thorough, factual markdown with exactly these sections:

## Overview
2-3 paragraphs: who built it, model family and lineage, release context, why it matters.

## Architecture & training
Architecture type (dense/MoE, encoder/decoder), context window, tokenizer, training data and techniques as far as known.

## What it does
Detailed capabilities walk-through with concrete examples of inputs/outputs.

## How it compares to similar models
Name at least 3 concrete competitor models and compare quality, speed, size, and licensing. Cite benchmark numbers from the model card when available.

## What it is good for
At least 5 specific scenarios, each with a sentence of justification.

## Speed & performance
Expected inference speed: tokens/sec on typical consumer GPU, high-end GPU, and CPU-only; time-to-first-token feel; how speed changes with quantization and context length. Give concrete estimates and mark them as estimates.

## Minimum configuration to run locally
Be concrete: minimum RAM/VRAM per quantization level (e.g. Q4, Q8, FP16), minimum GPU examples (e.g. "RTX 3060 12GB"), CPU-only feasibility, disk space, and the exact command to run it if an Ollama/GGUF variant exists.

## Limitations
Honest weaknesses: quality gaps, languages, context limits, hallucination risks, licensing restrictions.

## Deployment options
Serving options (Ollama, vLLM, llama.cpp, cloud APIs), variant/quant availability.

## Verdict
Short paragraph: who should pick this model and who should look elsewhere.

Expand every section fully — do not compress to bullet fragments. Be specific and honest; if something is unknown, say so explicitly. No preamble before the first section.
