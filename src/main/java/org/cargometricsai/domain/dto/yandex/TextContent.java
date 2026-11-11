package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TextContent(
    @JsonProperty("type") String type,
    @JsonProperty("text") String text
) {}
