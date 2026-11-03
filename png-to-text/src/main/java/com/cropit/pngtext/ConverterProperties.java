package com.cropit.pngtext;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("png-text")
public record ConverterProperties(String inputDir, String outputDir, URI ollamaUrl,
        String model, String prompt, String promptFile, String extension, boolean overwrite,
        Duration connectTimeout, Duration readTimeout, int contextSize, int maxTokens) {

    public ConverterProperties {
        requireText(inputDir, "INPUT_DIR / png-text.input-dir");
        requireText(outputDir, "OUTPUT_DIR / png-text.output-dir");
        requireText(model, "OLLAMA_MODEL / png-text.model");
        if (ollamaUrl == null || ollamaUrl.getHost() == null
                || !("http".equals(ollamaUrl.getScheme()) || "https".equals(ollamaUrl.getScheme()))
                || ollamaUrl.getQuery() != null || ollamaUrl.getFragment() != null) {
            throw new IllegalArgumentException("OLLAMA_BASE_URL must be an HTTP(S) base URL");
        }
        if (extension == null || !extension.matches("[A-Za-z0-9][A-Za-z0-9_-]*")) {
            throw new IllegalArgumentException("OUTPUT_EXTENSION must be a simple extension without a dot (e.g. md or txt)");
        }
        if (promptFile == null || promptFile.isBlank()) {
            requireText(prompt, "CONVERSION_PROMPT / png-text.prompt");
        }
        if (connectTimeout == null || connectTimeout.isNegative() || connectTimeout.isZero()
                || readTimeout == null || readTimeout.isNegative() || readTimeout.isZero()) {
            throw new IllegalArgumentException("Connection and read timeouts must be positive durations");
        }
        if (contextSize <= 0 || maxTokens <= 0) {
            throw new IllegalArgumentException("CONTEXT_SIZE and MAX_TOKENS must be positive integers");
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be configured");
        }
    }
}
