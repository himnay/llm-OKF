package com.llm.okf.service;

import com.llm.okf.model.IngestResponse;
import io.micrometer.core.annotation.Timed;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.pdf.PagePdfDocumentReader;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class OkfIngestionService {

    @Timed(value = "okf.ingest.pdf", description = "PDF ingestion — extract text and write OKF markdown file")
    public IngestResponse ingestPdf(Resource pdfResource, String outputDir) throws IOException {
        PagePdfDocumentReader reader = new PagePdfDocumentReader(pdfResource);
        List<Document> docs = reader.get();

        String filename = pdfResource.getFilename() != null
                ? pdfResource.getFilename().replace(".pdf", "")
                : "unknown";
        String slug = filename.toLowerCase().replaceAll("[^a-z0-9]+", "-");

        String content = docs.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        String okfContent = buildOkfMarkdown(filename, slug, content);

        Path outputPath = Paths.get(outputDir, "references", slug + ".md");
        Files.createDirectories(outputPath.getParent());
        Files.writeString(outputPath, okfContent);

        log.info("Ingested PDF {} → {} ({} pages)", filename, outputPath, docs.size());
        return new IngestResponse("PDF ingested successfully", outputPath.toString(), docs.size());
    }

    private String buildOkfMarkdown(String title, String slug, String content) {
        return """
                ---
                type: reference
                title: %s
                tags: [pdf, ingested, %s]
                related: []
                ---

                # %s

                %s
                """.formatted(title, slug, title, content);
    }
}
