package com.llm.okf.models.service;

import com.llm.okf.models.model.HfModel;
import com.llm.okf.models.model.LlmModelDoc;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Derives catalog fields from raw Hugging Face metadata: parameter count (parsed from the model
 * name — see requirements §9, decision: Option A, no per-model detail calls), capability flags
 * from the pipeline tag, and local-run heuristics.
 */
@Component
public class ModelEnricher {

    private static final Set<String> TEXT_GEN = Set.of(
            "text-generation", "text2text-generation", "conversational");
    private static final Set<String> MULTIMODAL = Set.of(
            "image-text-to-text", "visual-question-answering", "video-text-to-text",
            "any-to-any", "image-to-text", "document-question-answering");
    private static final Set<String> AUDIO = Set.of(
            "automatic-speech-recognition", "text-to-speech", "audio-classification",
            "text-to-audio", "audio-to-audio", "voice-activity-detection");
    private static final Set<String> VISION = Set.of(
            "image-classification", "object-detection", "image-segmentation", "text-to-image",
            "image-to-image", "depth-estimation", "zero-shot-image-classification",
            "image-feature-extraction", "video-classification", "text-to-video",
            "unconditional-image-generation", "mask-generation", "zero-shot-object-detection");
    private static final Set<String> EMBEDDING = Set.of(
            "feature-extraction", "sentence-similarity");

    private static final Map<String, String> GOOD_FOR = Map.ofEntries(
            Map.entry("text-generation", "Chat assistants, text generation, code generation, instruction following."),
            Map.entry("text2text-generation", "Translation, summarization, and other text-to-text transformations."),
            Map.entry("conversational", "Multi-turn dialogue and chat applications."),
            Map.entry("automatic-speech-recognition", "Transcribing speech to text (voice notes, subtitles, call analytics)."),
            Map.entry("text-to-speech", "Generating natural-sounding speech from text."),
            Map.entry("text-to-audio", "Generating music or sound effects from text prompts."),
            Map.entry("audio-classification", "Classifying sounds, speaker traits, or audio events."),
            Map.entry("text-to-image", "Generating images from text prompts."),
            Map.entry("image-to-text", "Image captioning and OCR-style description."),
            Map.entry("image-text-to-text", "Multimodal chat — answering questions about images."),
            Map.entry("visual-question-answering", "Answering questions about image content."),
            Map.entry("image-classification", "Labeling images by category."),
            Map.entry("object-detection", "Locating and labeling objects within images."),
            Map.entry("feature-extraction", "Producing embeddings for search, RAG, and clustering."),
            Map.entry("sentence-similarity", "Semantic search and sentence-level similarity scoring."),
            Map.entry("fill-mask", "Masked-token prediction — base model for fine-tuning."),
            Map.entry("token-classification", "Named-entity recognition and token tagging."),
            Map.entry("text-classification", "Sentiment analysis and text categorization."),
            Map.entry("translation", "Translating text between languages."),
            Map.entry("summarization", "Condensing long documents into summaries."),
            Map.entry("question-answering", "Extractive question answering over passages."),
            Map.entry("zero-shot-classification", "Classifying text against arbitrary labels without training."));

    // Matches "8B", "70b", "0.5B", "135M", "1.1b" as a token inside the model name
    private static final Pattern PARAMS = Pattern.compile("(?<![0-9.])(\\d+(?:\\.\\d+)?)([bmBM])(?![a-zA-Z0-9])");
    // Mixture-of-experts naming: "8x7B" → 8 × 7 = 56B total
    private static final Pattern MOE_PARAMS = Pattern.compile("(\\d+)x(\\d+(?:\\.\\d+)?)[bB](?![a-zA-Z0-9])");

    public LlmModelDoc enrich(HfModel hf, Instant syncedAt) {
        String id = hf.id();
        int slash = id.indexOf('/');
        String author = slash > 0 ? id.substring(0, slash) : "unknown";
        String name = slash > 0 ? id.substring(slash + 1) : id;
        String pipeline = hf.pipelineTag() == null ? "unknown" : hf.pipelineTag();
        List<String> tags = hf.tags() == null ? List.of() : hf.tags();

        Double params = parseParamsBillions(name);
        boolean ggufLike = tags.stream().anyMatch(t -> t.equals("gguf") || t.equals("ggml") || t.equals("ollama"))
                || "gguf".equalsIgnoreCase(hf.libraryName());

        return new LlmModelDoc(
                id, name, author, pipeline, hf.libraryName(), tags,
                extractLicense(tags),
                params,
                sizeCategory(params),
                canRunLocally(params, ggufLike),
                TEXT_GEN.contains(pipeline),
                MULTIMODAL.contains(pipeline) || tags.contains("multimodal"),
                AUDIO.contains(pipeline),
                VISION.contains(pipeline),
                EMBEDDING.contains(pipeline),
                GOOD_FOR.getOrDefault(pipeline,
                        "General-purpose model — see the Hugging Face model card for details."),
                hf.downloads(), hf.likes(), hf.createdAt(), hf.lastModified(), syncedAt);
    }

    /** Parses parameter count in billions from the model name; null when the name carries no size. */
    static Double parseParamsBillions(String name) {
        Matcher moe = MOE_PARAMS.matcher(name);
        if (moe.find()) {
            return Double.parseDouble(moe.group(1)) * Double.parseDouble(moe.group(2));
        }
        Matcher m = PARAMS.matcher(name);
        Double result = null;
        while (m.find()) {
            double value = Double.parseDouble(m.group(1));
            if (m.group(2).equalsIgnoreCase("m")) value /= 1000.0;
            result = value; // last size token wins (e.g. "Llama-3.1-8B" → 8)
        }
        return result;
    }

    static String sizeCategory(Double paramsBillions) {
        if (paramsBillions == null) return "unknown";
        if (paramsBillions < 3) return "small";
        if (paramsBillions <= 15) return "medium";
        if (paramsBillions <= 70) return "large";
        return "xl";
    }

    static boolean canRunLocally(Double paramsBillions, boolean ggufLike) {
        if (ggufLike) return true;
        return paramsBillions != null && paramsBillions <= 15;
    }

    private static String extractLicense(List<String> tags) {
        return tags.stream()
                .filter(t -> t.startsWith("license:"))
                .map(t -> t.substring("license:".length()))
                .findFirst()
                .orElse("unknown");
    }
}
