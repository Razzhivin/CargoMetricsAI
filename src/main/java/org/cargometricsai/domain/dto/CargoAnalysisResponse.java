package org.cargometricsai.domain.dto;

import java.util.UUID;

public record CargoAnalysisResponse(
    UUID requestId,
    String cargoType,
    String packaging,
    String referenceObjectFound,
    Dimensions singleUnitDimensionsM,
    double totalEstimatedVolumeM3,
    String estimatedWeightRangeKg,
    String recommendedVehicle,
    String logisticNotes
) {}
