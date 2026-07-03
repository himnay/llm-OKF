package com.llm.okf.models.client;

import com.llm.okf.models.config.LlmModelsProperties;
import com.llm.okf.models.model.HfModel;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.URI;
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
     * Fetches quality models from the Hugging Face Hub: pages through the feed sorted by
     * {@code sort} (downloads/likes/trendingScore, descending) and keeps only models passing
     * both quality gates ({@code min-downloads} AND {@code min-likes}) — no random long-tail
     * models. Stops at {@code min-models} qualified entries, when pages run out, or early once
     * the sorted field itself falls below its floor (everything after is worse).
     */
    public List<HfModel> fetchTopModels() {
        List<HfModel> models = new ArrayList<>();
        String sort = properties.sort() == null || properties.sort().isBlank() ? "downloads" : properties.sort();
        String url = properties.apiUrl() + "?limit=" + properties.pageSize() + "&sort=" + sort + "&direction=-1";
        int scanned = 0;

        while (url != null && models.size() < properties.minModels()) {
            log.debug("Fetching HF models page: {}", url);
            // URI.create: the next-page URL from the Link header is already encoded — template
            // expansion via .uri(String) would re-encode the pagination cursor and break it
            ResponseEntity<HfModel[]> response = huggingFaceRestClient.get()
                    .uri(URI.create(url))
                    .retrieve()
                    .toEntity(HfModel[].class);
            HfModel[] page = response.getBody();
            if (page == null || page.length == 0) break;

            for (HfModel model : page) {
                scanned++;
                if (qualifies(model)) models.add(model);
            }
            if (belowSortFloor(page[page.length - 1], sort)) break;
            url = nextLink(response.getHeaders().getFirst("Link"));
        }

        log.info("Fetched {} quality models from Hugging Face (scanned {}, gates: downloads>={}, likes>={})",
                models.size(), scanned, properties.minDownloads(), properties.minLikes());
        return models.size() > properties.minModels() ? models.subList(0, properties.minModels()) : models;
    }

    private boolean qualifies(HfModel model) {
        long downloads = model.downloads() == null ? 0 : model.downloads();
        long likes = model.likes() == null ? 0 : model.likes();
        return downloads >= properties.minDownloads() && likes >= properties.minLikes();
    }

    // Feed is sorted descending — once the sort field drops below its own floor, every
    // following page is worse; stop paging instead of scanning the 1.9M-model long tail.
    private boolean belowSortFloor(HfModel last, String sort) {
        return switch (sort) {
            case "downloads" -> (last.downloads() == null ? 0 : last.downloads()) < properties.minDownloads();
            case "likes" -> (last.likes() == null ? 0 : last.likes()) < properties.minLikes();
            default -> false;
        };
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
