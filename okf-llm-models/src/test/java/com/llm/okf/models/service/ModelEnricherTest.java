package com.llm.okf.models.service;

import com.llm.okf.models.model.HfModel;
import com.llm.okf.models.model.LlmModelDoc;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ModelEnricherTest {

    private final ModelEnricher enricher = new ModelEnricher();

    @Test
    @DisplayName("Parses parameter counts in billions from common model name patterns")
    void parsesParamsFromCommonNamePatterns() {
        assertThat(ModelEnricher.parseParamsBillions("Llama-3.1-8B-Instruct")).isEqualTo(8.0);
        assertThat(ModelEnricher.parseParamsBillions("Qwen2.5-72B")).isEqualTo(72.0);
        assertThat(ModelEnricher.parseParamsBillions("SmolLM-135M")).isEqualTo(0.135);
        assertThat(ModelEnricher.parseParamsBillions("phi-3.5-mini-3.8b-it")).isEqualTo(3.8);
    }

    @Test
    @DisplayName("Parses a mixture-of-experts name pattern (NxM) as the total parameter count")
    void parsesMixtureOfExpertsAsTotalParams() {
        assertThat(ModelEnricher.parseParamsBillions("Mixtral-8x7B-Instruct-v0.1")).isEqualTo(56.0);
    }

    @Test
    @DisplayName("Returns null when the model name contains no parameter size information")
    void returnsNullWhenNameCarriesNoSize() {
        assertThat(ModelEnricher.parseParamsBillions("gpt2")).isNull();
        assertThat(ModelEnricher.parseParamsBillions("bert-base-uncased")).isNull();
        assertThat(ModelEnricher.parseParamsBillions("whisper-large-v3")).isNull();
    }

    @Test
    @DisplayName("Classifies parameter counts into size categories across unknown/small/medium/large/xl boundaries")
    void sizeCategoryBoundaries() {
        assertThat(ModelEnricher.sizeCategory(null)).isEqualTo("unknown");
        assertThat(ModelEnricher.sizeCategory(0.5)).isEqualTo("small");
        assertThat(ModelEnricher.sizeCategory(8.0)).isEqualTo("medium");
        assertThat(ModelEnricher.sizeCategory(70.0)).isEqualTo("large");
        assertThat(ModelEnricher.sizeCategory(405.0)).isEqualTo("xl");
    }

    @Test
    @DisplayName("Determines whether a model can run locally based on size and GGUF packaging tag")
    void localRunHeuristics() {
        assertThat(ModelEnricher.canRunLocally(8.0, false)).isTrue();
        assertThat(ModelEnricher.canRunLocally(70.0, false)).isFalse();
        assertThat(ModelEnricher.canRunLocally(null, true)).isTrue();   // gguf tag wins
        assertThat(ModelEnricher.canRunLocally(null, false)).isFalse(); // unknown size, no local packaging
    }

    @Test
    @DisplayName("Enriches an HF model into a LlmModelDoc, deriving capabilities from its pipeline tag")
    void enrichDerivesCapabilitiesFromPipelineTag() {
        HfModel hf = new HfModel("meta-llama/Llama-3.1-8B-Instruct", "text-generation",
                "transformers", List.of("license:llama3.1", "text-generation-inference"),
                1000L, 50L, Instant.parse("2024-07-01T00:00:00Z"), Instant.parse("2024-08-01T00:00:00Z"));

        LlmModelDoc doc = enricher.enrich(hf, Instant.parse("2026-07-02T00:00:00Z"));

        assertThat(doc.author()).isEqualTo("meta-llama");
        assertThat(doc.name()).isEqualTo("Llama-3.1-8B-Instruct");
        assertThat(doc.license()).isEqualTo("llama3.1");
        assertThat(doc.paramsBillions()).isEqualTo(8.0);
        assertThat(doc.sizeCategory()).isEqualTo("medium");
        assertThat(doc.canRunLocally()).isTrue();
        assertThat(doc.textGenerative()).isTrue();
        assertThat(doc.multimodal()).isFalse();
        assertThat(doc.audio()).isFalse();
    }

    @Test
    @DisplayName("Flags audio and embedding models based on their speech-recognition or sentence-similarity pipeline")
    void enrichFlagsAudioAndEmbeddingPipelines() {
        HfModel whisper = new HfModel("openai/whisper-large-v3", "automatic-speech-recognition",
                "transformers", List.of(), 1L, 1L, null, null);
        HfModel minilm = new HfModel("sentence-transformers/all-MiniLM-L6-v2", "sentence-similarity",
                "sentence-transformers", List.of(), 1L, 1L, null, null);

        assertThat(enricher.enrich(whisper, Instant.now()).audio()).isTrue();
        assertThat(enricher.enrich(minilm, Instant.now()).embedding()).isTrue();
    }
}
