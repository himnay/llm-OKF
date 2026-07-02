package com.llm.okf.navigator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.llm.okf.config.OkfProperties;
import com.llm.okf.event.IndexSyncedEvent;
import com.llm.okf.model.OkfFile;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OkfNavigatorTest {

    private static final String TEMPLATE = "index: {index} query: {query} max: {maxFilesPerQuery}";

    @TempDir
    Path knowledgeBase;

    private ChatClient chatClient;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> valueOps;
    private OkfNavigator navigator;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        chatClient = mock(ChatClient.class, RETURNS_DEEP_STUBS);
        redis = mock(StringRedisTemplate.class);
        valueOps = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(valueOps);

        OkfProperties properties = new OkfProperties(
                knowledgeBase.toString(), 3, "nav-model", "extract-model", null);
        navigator = new OkfNavigator(
                chatClient, properties,
                new ByteArrayResource(TEMPLATE.getBytes()),
                new ObjectMapper(), redis);
    }

    private void writeFile(String name, String content) throws Exception {
        Files.writeString(knowledgeBase.resolve(name), content);
    }

    @Test
    void readIndexFileReturnsCachedValueWithoutTouchingDisk() {
        when(valueOps.get("okf:index")).thenReturn("cached index");

        assertThat(navigator.readIndexFile()).isEqualTo("cached index");
    }

    @Test
    void readIndexFileLoadsFromDiskAndCachesOnMiss() throws Exception {
        when(valueOps.get("okf:index")).thenReturn(null);
        writeFile("index.md", "# Index\n- doc-a.md");

        assertThat(navigator.readIndexFile()).contains("doc-a.md");
        verify(valueOps).set(eq("okf:index"), eq("# Index\n- doc-a.md"), any(java.time.Duration.class));
    }

    @Test
    void readIndexFileDoesNotCacheMissingIndex() {
        when(valueOps.get("okf:index")).thenReturn(null);

        assertThat(navigator.readIndexFile()).isEmpty();
        verify(valueOps, org.mockito.Mockito.never()).set(anyString(), anyString(), any(java.time.Duration.class));
    }

    @Test
    void indexSyncedEventEvictsCache() {
        navigator.onIndexSynced(new IndexSyncedEvent(this));

        verify(redis).delete("okf:index");
    }

    @Test
    void findRelevantFilesReturnsEmptyWhenIndexBlank() {
        assertThat(navigator.findRelevantFiles("query", "")).isEmpty();
    }

    @Test
    void findRelevantFilesParsesJsonArrayFromLlmResponse() {
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("Here you go: [\"docs/auth.md\", \"docs/api.md\"]");

        List<String> files = navigator.findRelevantFiles("how does auth work?", "# Index");

        assertThat(files).containsExactly("docs/auth.md", "docs/api.md");
    }

    @Test
    void findRelevantFilesReturnsEmptyOnMalformedLlmResponse() {
        when(chatClient.prompt().user(anyString()).call().content())
                .thenReturn("I could not find anything relevant.");

        assertThat(navigator.findRelevantFiles("query", "# Index")).isEmpty();
    }

    @Test
    void loadFilesParsesFrontmatterAndBody() throws Exception {
        writeFile("doc-a.md", """
                ---
                title: Auth Service
                type: code
                tags: [auth, security]
                related: [doc-b.md]
                ---
                The auth service validates tokens.""");

        List<OkfFile> files = navigator.loadFiles(List.of("doc-a.md"));

        assertThat(files).hasSize(1);
        OkfFile file = files.getFirst();
        assertThat(file.title()).isEqualTo("Auth Service");
        assertThat(file.type()).isEqualTo("code");
        assertThat(file.tags()).containsExactly("auth", "security");
        assertThat(file.related()).containsExactly("doc-b.md");
        assertThat(file.content()).isEqualTo("The auth service validates tokens.");
    }

    @Test
    void loadFilesWithoutFrontmatterUsesDefaults() throws Exception {
        writeFile("plain.md", "Just a body, no frontmatter.");

        OkfFile file = navigator.loadFiles(List.of("plain.md")).getFirst();

        assertThat(file.title()).isEqualTo("plain.md");
        assertThat(file.type()).isEqualTo("unknown");
        assertThat(file.content()).isEqualTo("Just a body, no frontmatter.");
    }

    @Test
    void loadFilesWithBrokenFrontmatterFallsBackGracefully() throws Exception {
        writeFile("broken.md", "---\n:{bad yaml\n---\nBody survives.");

        OkfFile file = navigator.loadFiles(List.of("broken.md")).getFirst();

        assertThat(file.title()).isEqualTo("broken.md");
        assertThat(file.content()).isEqualTo("Body survives.");
    }

    @Test
    void missingFileYieldsEmptyBodyInsteadOfThrowing() {
        OkfFile file = navigator.loadFiles(List.of("does-not-exist.md")).getFirst();

        assertThat(file.content()).isEmpty();
    }

    @Test
    void listAllFilesExcludesIndexAndDotfiles() throws Exception {
        writeFile("index.md", "# Index");
        writeFile(".hidden.md", "secret");
        writeFile("doc-a.md", "---\ntitle: A\n---\nbody a");
        writeFile("doc-b.md", "body b");

        List<OkfFile> files = navigator.listAllFiles();

        assertThat(files).extracting(OkfFile::path).containsExactly("doc-a.md", "doc-b.md");
    }

    @Test
    void listAllFilesReturnsEmptyWhenBaseDirMissing() {
        OkfProperties properties = new OkfProperties(
                knowledgeBase.resolve("nope").toString(), 3, "m", "m", null);
        OkfNavigator freshNavigator = new OkfNavigator(
                chatClient, properties, new ByteArrayResource(TEMPLATE.getBytes()),
                new ObjectMapper(), redis);

        assertThat(freshNavigator.listAllFiles()).isEmpty();
    }
}
