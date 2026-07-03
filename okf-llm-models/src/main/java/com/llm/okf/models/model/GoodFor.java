package com.llm.okf.models.model;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

/** What a model is good for, keyed by its Hugging Face pipeline tag. */
public enum GoodFor {
    TEXT_GENERATION("text-generation", "Chat assistants, text generation, code generation, instruction following."),
    TEXT2TEXT_GENERATION("text2text-generation", "Translation, summarization, and other text-to-text transformations."),
    CONVERSATIONAL("conversational", "Multi-turn dialogue and chat applications."),
    AUTOMATIC_SPEECH_RECOGNITION("automatic-speech-recognition", "Transcribing speech to text (voice notes, subtitles, call analytics)."),
    TEXT_TO_SPEECH("text-to-speech", "Generating natural-sounding speech from text."),
    TEXT_TO_AUDIO("text-to-audio", "Generating music or sound effects from text prompts."),
    AUDIO_CLASSIFICATION("audio-classification", "Classifying sounds, speaker traits, or audio events."),
    TEXT_TO_IMAGE("text-to-image", "Generating images from text prompts."),
    IMAGE_TO_TEXT("image-to-text", "Image captioning and OCR-style description."),
    IMAGE_TEXT_TO_TEXT("image-text-to-text", "Multimodal chat — answering questions about images."),
    VISUAL_QUESTION_ANSWERING("visual-question-answering", "Answering questions about image content."),
    IMAGE_CLASSIFICATION("image-classification", "Labeling images by category."),
    OBJECT_DETECTION("object-detection", "Locating and labeling objects within images."),
    FEATURE_EXTRACTION("feature-extraction", "Producing embeddings for search, RAG, and clustering."),
    SENTENCE_SIMILARITY("sentence-similarity", "Semantic search and sentence-level similarity scoring."),
    FILL_MASK("fill-mask", "Masked-token prediction — base model for fine-tuning."),
    TOKEN_CLASSIFICATION("token-classification", "Named-entity recognition and token tagging."),
    TEXT_CLASSIFICATION("text-classification", "Sentiment analysis and text categorization."),
    TRANSLATION("translation", "Translating text between languages."),
    SUMMARIZATION("summarization", "Condensing long documents into summaries."),
    QUESTION_ANSWERING("question-answering", "Extractive question answering over passages."),
    ZERO_SHOT_CLASSIFICATION("zero-shot-classification", "Classifying text against arbitrary labels without training."),
    GENERAL("", "General-purpose model — see the Hugging Face model card for details.");

    private static final Map<String, GoodFor> BY_TAG = Arrays.stream(values())
            .collect(Collectors.toMap(g -> g.pipelineTag, g -> g));

    private final String pipelineTag;
    private final String description;

    GoodFor(String pipelineTag, String description) {
        this.pipelineTag = pipelineTag;
        this.description = description;
    }

    /** Human-readable sentence describing what models with this pipeline tag are good for. */
    public String description() {
        return description;
    }

    /** Resolves a Hugging Face pipeline tag to its capability description; {@link #GENERAL} for unknown tags. */
    public static GoodFor fromPipelineTag(String tag) {
        return BY_TAG.getOrDefault(tag, GENERAL);
    }
}
