package com.llm.okf.mcp.service;

import com.llm.okf.mcp.model.ModelDetailDoc;
import com.llm.okf.models.model.LlmModelDoc;
import com.llm.okf.models.repository.LlmModelRepository;
import com.llm.okf.models.service.OkfMaterializer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * MCP tools over the OKF LLM-model knowledge base. Exposed two ways: to external agents through
 * the MCP server endpoint, and to the in-app chat as default tools — the chat LLM calls these to
 * pull OKF data and then go to the internet for a richer description.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LlmModelMcpTools {

    private final LlmModelRepository repository;
    private final MongoTemplate mongoTemplate;
    private final OkfMaterializer materializer;
    private final ModelWebEnricher webEnricher;

    /**
     * Case-insensitive search over model id, tags, and type, ordered by downloads.
     *
     * @param query keyword or model name fragment
     * @param limit max rows (null → 10, capped at 50)
     * @return one line per match, or a not-found message
     */
    @Tool(description = "Search the OKF LLM model knowledge base by name or keyword. "
            + "Returns matching models with id, type, parameter size, and what they are good for. "
            + "Use this first to find the exact model id.")
    public String searchModels(
            @ToolParam(description = "Model name or keyword, e.g. 'llama', 'whisper', 'embedding'") String query,
            @ToolParam(description = "Max results, default 10", required = false) Integer limit) {
        Pattern pattern = Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE);
        Query mongoQuery = new Query(new Criteria().orOperator(
                        Criteria.where("_id").regex(pattern),
                        Criteria.where("tags").regex(pattern),
                        Criteria.where("modelType").regex(pattern)))
                .with(Sort.by(Sort.Direction.DESC, "downloads"))
                .limit(limit == null ? 10 : Math.min(limit, 50));
        List<LlmModelDoc> hits = mongoTemplate.find(mongoQuery, LlmModelDoc.class);
        if (hits.isEmpty()) return "No models found for: " + query;
        return hits.stream()
                .map(m -> "- %s | %s | %s params | local:%s | %s".formatted(
                        m.id(), m.modelType(),
                        m.paramsBillions() == null ? "?" : m.paramsBillions() + "B",
                        m.canRunLocally(), m.goodFor()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Renders the model's full OKF markdown document straight from its MongoDB record —
     * same content as the materialized file on disk, but always current.
     *
     * @param modelId exact Hugging Face model id
     * @return the OKF document, or a not-found message with guidance
     */
    @Tool(description = "Get the full OKF knowledge document for one model from the knowledge base: "
            + "capabilities, size, local-run info, license, downloads. Requires the exact model id "
            + "(use searchModels first).")
    public String getModelKnowledge(
            @ToolParam(description = "Exact Hugging Face model id, e.g. 'meta-llama/Llama-3.1-8B-Instruct'") String modelId) {
        return repository.findById(modelId)
                .map(materializer::render)
                .orElse("Model not in the OKF knowledge base: " + modelId
                        + ". Use searchModels to find valid ids.");
    }

    /**
     * Web-enriched deep profile: fetches the Hugging Face model card from the internet and has
     * the local LLM write a 1-2 page analysis (description, comparisons, speed, minimum local
     * configuration). Served from the MongoDB cache when fresh.
     *
     * @param modelId exact Hugging Face model id
     * @param refresh true regenerates even when a fresh cached profile exists
     * @return markdown profile with source/generation footer, or a not-found message
     */
    @Tool(description = "Fetch a rich, up-to-date profile of a model from the internet: detailed "
            + "description, how it compares to similar models, best use cases, limitations, hardware "
            + "needs. Sources the Hugging Face model card and analyzes it. Cached; slow (~LLM call) "
            + "on first request for a model.")
    public String enrichModelDetails(
            @ToolParam(description = "Exact Hugging Face model id (use searchModels first)") String modelId,
            @ToolParam(description = "Force regeneration ignoring the cache, default false", required = false) Boolean refresh) {
        return repository.findById(modelId)
                .map(m -> {
                    ModelDetailDoc detail = webEnricher.enrich(m, Boolean.TRUE.equals(refresh));
                    return "# " + m.id() + "\n\n" + detail.enrichedDescription()
                            + "\n\n---\n_Source: " + (detail.cardFound() ? "Hugging Face model card" : "metadata only (no card)")
                            + ", generated " + detail.generatedAt() + " by " + detail.generatorModel() + "_";
                })
                .orElse("Model not in the OKF knowledge base: " + modelId
                        + ". Use searchModels to find valid ids.");
    }
}
