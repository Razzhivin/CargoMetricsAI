package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageContent(
    @JsonProperty("type") String type,
    @JsonProperty("image_bytes") String imageBytes
) {}
