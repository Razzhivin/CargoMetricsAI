package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageUrlInfo(
    @JsonProperty("url") String url
) {}
