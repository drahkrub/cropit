package com.cropit.pngtext;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.content.Media;
import org.springframework.core.io.FileSystemResource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeTypeUtils;

@Service
public class BatchConverter {
    private static final Logger log = LoggerFactory.getLogger(BatchConverter.class);
    private final ConverterProperties properties;
    private final ChatModel model;

    public BatchConverter(ConverterProperties properties, ChatModel model) {
        this.properties = properties;
        this.model = model;
    }

    public record Result(int written, int skipped, int failed) { }

    public Result convert() throws IOException {
        Path input = Path.of(properties.inputDir()).toRealPath();
        if (!Files.isDirectory(input)) {
            throw new IOException("Input is not a directory: " + input);
        }
        Path output = Path.of(properties.outputDir()).toAbsolutePath().normalize();
        Files.createDirectories(output);
        if (Files.isSameFile(input, output)) {
            throw new IOException("Input and output directories must be different");
        }
        String prompt = properties.promptFile() == null || properties.promptFile().isBlank()
                ? properties.prompt()
                : Files.readString(Path.of(properties.promptFile()), StandardCharsets.UTF_8);
        if (prompt.isBlank()) {
            throw new IOException("Prompt must not be empty");
        }
        List<Path> images;
        try (var entries = Files.list(input)) {
            images = entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted().toList();
        }
        // Detect ambiguous output names before sending any requests.
        var names = new HashSet<String>();
        for (Path image : images) {
            if (!names.add(outputName(image).toLowerCase(Locale.ROOT))) {
                throw new IOException("Multiple PNGs map to the same output name: " + image.getFileName());
            }
        }
        if (images.isEmpty()) {
            throw new IOException("No PNG files found directly in " + input);
        }
        log.info("Converting {} PNGs from {} to {} using model {}", images.size(), input, output, properties.model());
        int written = 0;
        int skipped = 0;
        int failed = 0;
        for (Path image : images) {
            Path target = output.resolve(outputName(image));
            if (!properties.overwrite() && Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                skipped++;
                log.info("Skipping existing output: {}", target);
                continue;
            }
            try {
                log.info("Processing {}/{}: {}", written + skipped + failed + 1, images.size(), image.getFileName());
                var message = UserMessage.builder().text(prompt)
                        .media(new Media(MimeTypeUtils.IMAGE_PNG, new FileSystemResource(image))).build();
                var response = model.call(new Prompt(message));
                String text = response == null || response.getResult() == null
                        ? null : response.getResult().getOutput().getText();
                if (text == null || text.isBlank()) {
                    throw new IOException("Model returned no text");
                }
                if ("length".equalsIgnoreCase(response.getResult().getMetadata().getFinishReason())) {
                    throw new IOException("Model output was truncated; increase MAX_TOKENS / CONTEXT_SIZE");
                }
                writeOutput(target, text);
                written++;
            } catch (Exception ex) {
                failed++;
                log.error("Failed {}: {}", image.getFileName(), ex.getMessage());
            }
        }
        log.info("Finished: {} written, {} skipped, {} failed", written, skipped, failed);
        return new Result(written, skipped, failed);
    }

    private String outputName(Path image) {
        String name = image.getFileName().toString();
        return name.substring(0, name.length() - 4) + "." + properties.extension();
    }

    private void writeOutput(Path target, String text) throws IOException {
        // Publish only complete responses. A failed conversion leaves existing output intact.
        Path temporary = Files.createTempFile(target.getParent(), ".png-to-text-", ".tmp");
        try {
            Files.writeString(temporary, text, StandardCharsets.UTF_8);
            if (properties.overwrite()) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(temporary, target);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
