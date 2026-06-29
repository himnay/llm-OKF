---
type: concept
title: Local LLM Setup
tags: [ollama, comfyui, flux, rocm, amd, local-ai]
related: [references/ollama-models.md]
---

# Local LLM Setup

## Hardware
- CPU: AMD Ryzen AI 9 HX 370 (24 cores)
- GPU: AMD Radeon 890M (iGPU, gfx1150, ~29GB shared VRAM)
- RAM: 60GB

## Ollama Setup
Ollama runs on port 11434. Models stored in ~/.ollama/models.

### Installed Models
- llama3.1:8b — general chat, fast on CPU
- qwen2.5-coder:32b — best coding quality, slow (CPU only)
- qwen3-embedding:8b — embedding model ONLY, not chat

## ComfyUI / Image Generation
ComfyUI runs on port 8188. Uses FLUX model (GGUF).

### ROCm Fix
AMD Radeon 890M (gfx1150) not in default PyTorch ROCm build.
Fix: launch with `HSA_OVERRIDE_GFX_VERSION=11.0.2`

### FLUX Workflow
- UnetLoaderGGUF: flux-model.gguf
- DualCLIPLoader: t5xxl_fp8_e4m3fn.safetensors + clip_l.safetensors, device=cpu
- VAELoader: ae.safetensors
- KSampler: steps=4, cfg=1.0, euler, simple
