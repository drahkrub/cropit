package com.cropit.pngtext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.net.URI;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.jayway.jsonpath.JsonPath;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class BatchConverterTest {
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jRZkAAAAASUVORK5CYII=");

    @TempDir Path temporary;

    private ConverterProperties properties(boolean overwrite, String extension, String promptFile, URI url) {
        return new ConverterProperties(temporary.resolve("input").toString(),
                temporary.resolve("output").toString(), url, "test-vision:latest",
                "Transcribe exactly.", promptFile, extension, overwrite,
                Duration.ofSeconds(2), Duration.ofSeconds(2), 16384, 8192);
    }

    private ConverterProperties properties(boolean overwrite) {
        return properties(overwrite, "md", "", URI.create("http://localhost:11434"));
    }

    private void image(String filename) throws Exception {
        Path input = Files.createDirectories(temporary.resolve("input"));
        Files.write(input.resolve(filename), PNG);
    }

    private ChatResponse response(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    @Test
    void realSpringAiSendsImageAndPromptToOllamaAndWritesUtf8PerImage() throws Exception {
        image("page-002.PNG");
        image("page-001.png");
        Files.writeString(temporary.resolve("input/ignore.txt"), "ignore");
        Files.createDirectories(temporary.resolve("input/nested"));
        Files.write(temporary.resolve("input/nested/ignore.png"), PNG);
        var requests = new CopyOnWriteArrayList<String>();
        var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), UTF_8));
            byte[] body = """
                    {"model":"test-vision:latest","created_at":"2026-11-02T12:00:00Z",
                     "message":{"role":"assistant","content":"# Überschrift\\n\\nInhalt äöü"},
                     "done":true,"done_reason":"stop","prompt_eval_count":20,"eval_count":10}
                    """.getBytes(UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI url = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var properties = properties(false, "md", "", url);
            var model = new OllamaConfiguration().chatModel(properties);
            var result = new BatchConverter(properties, model).convert();
            assertThat(result).isEqualTo(new BatchConverter.Result(2, 0, 0));
            assertThat(Files.readString(temporary.resolve("output/page-001.md")))
                    .isEqualTo("# Überschrift\n\nInhalt äöü");
            assertThat(Files.readString(temporary.resolve("output/page-002.md")))
                    .isEqualTo("# Überschrift\n\nInhalt äöü");
            assertThat(requests).hasSize(2);
            for (String body : requests) {
                var json = JsonPath.parse(body);
                assertThat(json.<String>read("$.model")).isEqualTo("test-vision:latest");
                assertThat(json.<Boolean>read("$.stream")).isFalse();
                assertThat(json.<Boolean>read("$.think")).isFalse();
                assertThat(json.<List<?>>read("$.messages")).hasSize(1);
                assertThat(json.<String>read("$.messages[0].content")).isEqualTo("Transcribe exactly.");
                assertThat(json.<String>read("$.messages[0].images[0]"))
                        .isEqualTo(Base64.getEncoder().encodeToString(PNG));
                assertThat(json.<Integer>read("$.options.num_ctx")).isEqualTo(16384);
                assertThat(json.<Integer>read("$.options.num_predict")).isEqualTo(8192);
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void skipsExistingOutputAndResumesWithRemainingFilesInOrder() throws Exception {
        image("page-003.png");
        image("page-002.png");
        image("page-001.png");
        Files.createDirectories(temporary.resolve("output"));
        Files.writeString(temporary.resolve("output/page-001.md"), "Keep me");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenReturn(response("Second"), response("Third"));

        var result = new BatchConverter(properties(false), model).convert();

        assertThat(result).isEqualTo(new BatchConverter.Result(2, 1, 0));
        assertThat(Files.readString(temporary.resolve("output/page-001.md"))).isEqualTo("Keep me");
        assertThat(Files.readString(temporary.resolve("output/page-002.md"))).isEqualTo("Second");
        assertThat(Files.readString(temporary.resolve("output/page-003.md"))).isEqualTo("Third");
        verify(model, times(2)).call(any(Prompt.class));
    }

    @Test
    void continuesAfterFailureAndEmptyResponseAndKeepsOldOutput() throws Exception {
        image("page-001.png");
        image("page-002.png");
        image("page-003.png");
        Files.createDirectories(temporary.resolve("output"));
        Files.writeString(temporary.resolve("output/page-001.md"), "Previous result");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class)))
                .thenThrow(new IllegalStateException("Server unavailable"))
                .thenReturn(response(" "), response("Success"));

        var result = new BatchConverter(properties(true), model).convert();

        assertThat(result).isEqualTo(new BatchConverter.Result(1, 0, 2));
        assertThat(Files.readString(temporary.resolve("output/page-001.md"))).isEqualTo("Previous result");
        assertThat(temporary.resolve("output/page-002.md")).doesNotExist();
        assertThat(Files.readString(temporary.resolve("output/page-003.md"))).isEqualTo("Success");
        try (var files = Files.list(temporary.resolve("output"))) {
            assertThat(files.map(path -> path.getFileName().toString()).toList())
                    .containsExactlyInAnyOrder("page-001.md", "page-003.md");
        }
    }

    @Test
    void customPromptFileAndExtensionAndExplicitOverwrite() throws Exception {
        image("page.png");
        Path prompt = temporary.resolve("prompt.txt");
        Files.writeString(prompt, "Nur Klartext, einschließlich äöü.");
        Files.createDirectories(temporary.resolve("output"));
        Files.writeString(temporary.resolve("output/page.txt"), "Old");
        var model = mock(ChatModel.class);
        when(model.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt request = invocation.getArgument(0);
            assertThat(request.getContents()).contains("Nur Klartext, einschließlich äöü.");
            return response("New");
        });

        var config = properties(true, "txt", prompt.toString(), URI.create("http://localhost:11434"));
        assertThat(new BatchConverter(config, model).convert()).isEqualTo(new BatchConverter.Result(1, 0, 0));
        assertThat(Files.readString(temporary.resolve("output/page.txt"))).isEqualTo("New");
    }

    @Test
    void rejectsAmbiguousNamesBeforeCallingModel() throws Exception {
        image("page.png");
        image("page.PNG");
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> new BatchConverter(properties(false), model).convert())
                .hasMessageContaining("same output name");
        verifyNoInteractions(model);
    }

    @Test
    void rejectsEmptyInputDirectory() throws Exception {
        Files.createDirectories(temporary.resolve("input"));
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> new BatchConverter(properties(false), model).convert())
                .hasMessageContaining("No PNG files");
        verifyNoInteractions(model);
    }

    @Test
    void truncatedResponseIsNotSaved() throws Exception {
        image("page.png");
        var model = mock(ChatModel.class);
        var generation = new Generation(new AssistantMessage("Incomplete"),
                ChatGenerationMetadata.builder().finishReason("length").build());
        when(model.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(generation)));
        assertThat(new BatchConverter(properties(false), model).convert())
                .isEqualTo(new BatchConverter.Result(0, 0, 1));
        assertThat(temporary.resolve("output/page.md")).doesNotExist();
    }

    @Test
    void rejectsSameSourceAndTarget() throws Exception {
        image("page.png");
        var original = properties(false);
        var config = new ConverterProperties(original.inputDir(), original.inputDir(), original.ollamaUrl(),
                original.model(), original.prompt(), original.promptFile(), original.extension(),
                original.overwrite(), original.connectTimeout(), original.readTimeout(),
                original.contextSize(), original.maxTokens());
        var model = mock(ChatModel.class);
        assertThatThrownBy(() -> new BatchConverter(config, model).convert())
                .hasMessageContaining("must be different");
        verifyNoInteractions(model);
    }
}
