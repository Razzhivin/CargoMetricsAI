package org.cargometricsai.service;

import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

public interface CargoAnalysisService {
    CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata);
}
