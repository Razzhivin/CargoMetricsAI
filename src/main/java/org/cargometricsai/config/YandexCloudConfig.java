package org.cargometricsai.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class YandexCloudConfig {

    @Value("${yandex.api.token}")
    private String apiToken;

    @Value("${yandex.api.folder-id}")
    private String folderId;

    @Bean
    public RestClient yandexRestClient() {
        return RestClient.builder()
                .baseUrl("https://ai.api.cloud.yandex.net/v1")
                .defaultHeader("Authorization", "Api-Key " + apiToken)
                .defaultHeader("OpenAI-Project", folderId)
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return mapper;
    }
}
