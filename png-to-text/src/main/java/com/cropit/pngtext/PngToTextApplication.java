package com.cropit.pngtext;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.ExitCodeGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
@EnableConfigurationProperties(ConverterProperties.class)
public class PngToTextApplication {

    public static void main(String[] args) {
        var context = SpringApplication.run(PngToTextApplication.class, args);
        System.exit(SpringApplication.exit(context));
    }

    @Bean
    BatchRunner batchRunner(BatchConverter converter) {
        return new BatchRunner(converter);
    }

    static final class BatchRunner implements ApplicationRunner, ExitCodeGenerator {
        private final BatchConverter converter;
        private int exitCode;

        BatchRunner(BatchConverter converter) {
            this.converter = converter;
        }

        @Override
        public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
            exitCode = converter.convert().failed() == 0 ? 0 : 1;
        }

        @Override
        public int getExitCode() {
            return exitCode;
        }
    }
}
