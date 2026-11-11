package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageUrlContent(
    @JsonProperty("type") String type,
    @JsonProperty("image_url") ImageUrlInfo imageUrl
) {}
