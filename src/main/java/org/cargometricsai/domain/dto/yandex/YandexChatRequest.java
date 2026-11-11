package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record YandexChatRequest(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<YandexMessage> messages,
        @JsonProperty("temperature") double temperature
) {}
