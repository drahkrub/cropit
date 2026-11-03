package com.cropit.pngtext;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

class ConfigurationTest {
    @TempDir Path temporary;

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(ConverterProperties.class)
    static class TestConfiguration { }

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(TestConfiguration.class)
                .withPropertyValues("INPUT_DIR=" + temporary.resolve("input"),
                        "OUTPUT_DIR=" + temporary.resolve("output"), "OLLAMA_MODEL=test-vision");
    }

    @Test
    void environmentPlaceholdersAndDefaultsAreBound() {
        runner().withPropertyValues("OLLAMA_BASE_URL=http://intranet:11434", "OUTPUT_EXTENSION=txt")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var config = context.getBean(ConverterProperties.class);
                    assertThat(config.ollamaUrl().toString()).isEqualTo("http://intranet:11434");
                    assertThat(config.model()).isEqualTo("test-vision");
                    assertThat(config.extension()).isEqualTo("txt");
                    assertThat(config.overwrite()).isFalse();
                    assertThat(config.readTimeout()).hasMinutes(10);
                    assertThat(config.prompt()).contains("Markdown");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"png-text.model=", "png-text.input-dir=", "png-text.output-dir=",
            "png-text.extension=../txt", "png-text.ollama-url=ftp://host",
            "png-text.read-timeout=0s", "png-text.context-size=-1", "png-text.max-tokens=0"})
    void rejectsInvalidConfiguration(String value) {
        runner().withPropertyValues(value).run(context -> assertThat(context).hasFailed());
    }
}
