package org.cargometricsai.controller;

import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.cargometricsai.service.CargoAnalysisService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

@RestController
@RequestMapping("/api/v1/cargo")
public class CargoAnalysisController {

    private final CargoAnalysisService cargoAnalysisService;

    public CargoAnalysisController(CargoAnalysisService cargoAnalysisService) {
        this.cargoAnalysisService = cargoAnalysisService;
    }

    @PostMapping(value = "/analyze", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<CargoAnalysisResponse> analyze(
            @RequestParam("images") List<MultipartFile> images,
            @RequestParam("channel") String channel,
            @RequestParam("externalUserId") String externalUserId) {

        if (!"TELEGRAM".equals(channel) && !"MAX_BOT".equals(channel)) {
            throw new IllegalArgumentException("Неподдерживаемый канал интеграции для МСБ");
        }

        String tenantId = "TELEGRAM".equals(channel) ? "DIMITROVGRAD_DRIVERS_COMMUNITY" : "MSB_DEFAULT_TENANT";
        RequestMetadata metadata = new RequestMetadata(tenantId, channel, externalUserId);

        return ResponseEntity.ok(cargoAnalysisService.analyzeCargoImages(images, metadata));
    }
}
