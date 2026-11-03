package com.cropit.pngtext;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;

@Configuration(proxyBeanMethods = false)
class OllamaConfiguration {

    @Bean
    ChatModel chatModel(ConverterProperties properties) {
        var httpClient = HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
        var requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        var api = OllamaApi.builder()
                .baseUrl(properties.ollamaUrl().toString())
                .restClientBuilder(RestClient.builder().requestFactory(requestFactory))
                .build();
        return OllamaChatModel.builder()
                .ollamaApi(api)
                .options(OllamaChatOptions.builder()
                        .model(properties.model())
                        .disableThinking()
                        .temperature(0.0)
                        .numCtx(properties.contextSize())
                        .numPredict(properties.maxTokens())
                        .build())
                .build();
    }
}
