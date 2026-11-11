package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YandexMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") Object content
) {}
