package com.llm.okf.models.client;

import com.llm.okf.models.config.LlmModelsProperties;
import com.llm.okf.models.model.HfModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class HuggingFaceClient {

    private final RestClient huggingFaceRestClient;
    private final LlmModelsProperties properties;

    /**
     * Fetches the most-downloaded models from the Hugging Face Hub, following {@code Link: rel="next"}
     * pagination until at least {@code min-models} are collected or pages run out.
     */
    public List<HfModel> fetchTopModels() {
        List<HfModel> models = new ArrayList<>();
        String url = properties.apiUrl() + "?limit=" + properties.pageSize() + "&sort=downloads&direction=-1";

        while (url != null && models.size() < properties.minModels()) {
            log.debug("Fetching HF models page: {}", url);
            ResponseEntity<HfModel[]> response = huggingFaceRestClient.get()
                    .uri(url)
                    .retrieve()
                    .toEntity(HfModel[].class);
            HfModel[] page = response.getBody();
            if (page == null || page.length == 0) break;
            models.addAll(Arrays.asList(page));
            url = nextLink(response.getHeaders().getFirst("Link"));
        }

        log.info("Fetched {} models from Hugging Face", models.size());
        return models.size() > properties.minModels() ? models.subList(0, properties.minModels()) : models;
    }

    // Parses the RFC 5988 Link header: <https://…?cursor=x>; rel="next"
    static String nextLink(String linkHeader) {
        if (linkHeader == null) return null;
        for (String part : linkHeader.split(",")) {
            String[] segments = part.split(";");
            if (segments.length >= 2 && segments[1].trim().equals("rel=\"next\"")) {
                return segments[0].trim().replaceAll("^<|>$", "");
            }
        }
        return null;
    }
}
