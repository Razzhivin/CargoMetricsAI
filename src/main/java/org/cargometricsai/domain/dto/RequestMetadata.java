package org.cargometricsai.domain.dto;

public record RequestMetadata(
    String tenantId,
    String sourceChannel,
    String externalUserId
) {}
