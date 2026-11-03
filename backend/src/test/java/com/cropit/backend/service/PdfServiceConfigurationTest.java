package com.cropit.backend.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class PdfServiceConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withUserConfiguration(PdfService.class);

    @Test
    void defaultDpiIs400() {
        contextRunner.run(context ->
                assertThat(ReflectionTestUtils.getField(context.getBean(PdfService.class), "renderDpi"))
                        .isEqualTo(400));
    }

    @Test
    void environmentVariableOverridesDpi() {
        contextRunner.withPropertyValues("CROPIT_RENDER_DPI=600").run(context ->
                assertThat(ReflectionTestUtils.getField(context.getBean(PdfService.class), "renderDpi"))
                        .isEqualTo(600));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "2.5"})
    void invalidDpiPreventsStartup(String value) {
        contextRunner.withPropertyValues("cropit.render.dpi=" + value).run(context ->
                assertThat(context).hasFailed());
    }

    @Test
    void defaultTargetIs2048() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(PdfService.class), "targetLongSide"))
                    .isEqualTo(2048);
        });
    }

    @Test
    void environmentVariablePlaceholderOverridesDefault() {
        contextRunner.withPropertyValues("CROPIT_TARGET_LONG_SIDE=3072").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(PdfService.class), "targetLongSide"))
                    .isEqualTo(3072);
        });
    }

    @Test
    void springPropertyOverridesDefault() {
        contextRunner.withPropertyValues("cropit.render.target-long-side=1024").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(ReflectionTestUtils.getField(context.getBean(PdfService.class), "targetLongSide"))
                    .isEqualTo(1024);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "invalid", "2.5"})
    void invalidTargetPreventsStartup(String value) {
        contextRunner.withPropertyValues("CROPIT_TARGET_LONG_SIDE=" + value).run(context ->
                assertThat(context).hasFailed());
    }
}
