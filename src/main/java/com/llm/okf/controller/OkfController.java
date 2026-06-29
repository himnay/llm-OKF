package com.llm.okf.controller;

import com.llm.okf.model.ChatRequest;
import com.llm.okf.model.ChatResponse;
import com.llm.okf.model.IngestResponse;
import com.llm.okf.model.OkfFile;
import com.llm.okf.navigator.OkfNavigator;
import com.llm.okf.service.OkfChatService;
import com.llm.okf.service.OkfIngestionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Tag(name = "OKF", description = "Open Knowledge Format — RAG replacement using structured markdown knowledge base")
@RestController
@RequestMapping("/api/v1/okf")
@RequiredArgsConstructor
public class OkfController {

    private final OkfChatService chatService;
    private final OkfIngestionService ingestionService;
    private final OkfNavigator navigator;

    @Operation(summary = "Ask a question — OKF navigator finds relevant files, LLM answers with full context")
    @PostMapping("/chat")
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        return chatService.chat(request);
    }

    @Operation(summary = "Stream answer token-by-token via Server-Sent Events")
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> stream(@Valid @RequestBody ChatRequest request) {
        return chatService.stream(request);
    }

    @Operation(summary = "Show the OKF index — the master navigation file agents read first")
    @GetMapping("/index")
    public ResponseEntity<String> index() {
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_PLAIN)
                .body(navigator.loadIndex());
    }

    @Operation(summary = "List all OKF knowledge files with parsed frontmatter metadata")
    @GetMapping("/files")
    public List<OkfFile> files() {
        return navigator.listAllFiles();
    }

    @Operation(summary = "Ingest a PDF file into the OKF knowledge base as a structured markdown reference")
    @PostMapping(value = "/ingest/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public IngestResponse ingestPdf(@RequestParam("file") MultipartFile file) throws IOException {
        Path tmp = Files.createTempFile("okf-ingest-", ".pdf");
        file.transferTo(tmp);
        String outputDir = System.getProperty("java.io.tmpdir") + "/okf-output";
        return ingestionService.ingestPdf(new FileSystemResource(tmp.toFile()), outputDir);
    }
}
