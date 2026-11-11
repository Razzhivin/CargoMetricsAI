package org.cargometricsai.domain.dto.yandex;

public record CompletionOptions(boolean stream, double temperature, int maxTokens) {}
