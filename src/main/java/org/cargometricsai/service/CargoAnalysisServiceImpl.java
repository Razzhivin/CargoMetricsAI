package org.cargometricsai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.Dimensions;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.cargometricsai.domain.dto.yandex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CargoAnalysisServiceImpl implements CargoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CargoAnalysisServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
            Ты — опытный ИИ-логист для транспортных компаний. Твоя задача — проанализировать фото и выдать сбалансированный технический отчет по грузу в формате JSON. Заполняй поля так, чтобы числовые параметры и тип машины были точными, а текстовые рекомендации — развернутыми и полезными для водителя.

            ИНСТРУКЦИИ ПО ЗАПОЛНЕНИЮ JSON:
            1. cargo_type: Напиши подробно, что на фото, включая материал, состояние (б/у/новое) и конструктивные особенности. Не трать место на перечисление того, чего на фото явно нет.
            2. packaging: Тип упаковки. Если груз грязный (в цементе, масле, земле), обязательно укажи это.
            3. reference_object_found: Маркер масштаба (колесо, дверь, кирпич и т.д.).
            4. single_unit_dimensions_m: Габариты одной единицы (length, width, height в метрах).
            5. total_estimated_volume_m3: Общий объем партии (число в м³).
            6. estimated_weight_range_kg: Физический вес (диапазон от/до в кг). Обязательно учитывай пустотелость, каркасность и полости объектов (не считай воздух как монолитный металл).
            7. recommended_vehicle: СТРОГО одна текстовая строка из списка ниже для автоматического подбора тарифа бэкендом. Добавление постороннего текста в это поле запрещено.
               Список для выбора:
               - "Газель Стандарт (3м, до 1.5т)"
               - "Газель Удлинённая (4.2м, до 1.5т)"
               - "Газель Увеличенная (5м, до 1.5т)"
               - "Среднетоннажный грузовик (ГАЗон Некст до 5т)"
            8. logistic_notes: Подробные, развернутые и живые практические советы по транспортировке. Опиши специфику груза: хрупкие выступающие элементы (моторы, розетки, ручки), места, за которые нельзя крепить, риски опрокидывания из-за колес или центра тяжести. Если груз грязный — развернуто укажи требования к защите кузова (пленка, картон).

            Формат ответа:
            {
              "cargo_type": "детальное описание груза и особенностей",
              "packaging": "упаковка и чистота",
              "reference_object_found": "ориентир масштаба",
              "single_unit_dimensions_m": {"length": 0.00, "width": 0.00, "height": 0.00},
              "total_estimated_volume_m3": 0.00,
              "estimated_weight_range_kg": "от 00 до 00",
              "recommended_vehicle": "Выбранная строка из списка",
              "logistic_notes": "профессиональные развернутые рекомендации по укладке, крепежу ремнями, защите от грязи и хрупким точкам"
            }
            """;

    @Value("${yandex.api.folder-id}")
    private String folderId;

    private final RestClient yandexRestClient;
    private final ObjectMapper objectMapper;

    public CargoAnalysisServiceImpl(
            @Qualifier("yandexRestClient") RestClient yandexRestClient,
            ObjectMapper objectMapper) {
        this.yandexRestClient = yandexRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata) {
        validateImages(images);

        String dynamicModelUri = "gpt://" + folderId + "/deepseek-v4.1-flash/latest";

        List<YandexMessage> messages = new ArrayList<>();
        messages.add(new YandexMessage("system", SYSTEM_PROMPT));

        List<Object> userContents = new ArrayList<>();
        userContents.add(new TextContent("text", "Проанализируй этот груз по фотографии, определи габариты, вес, загрязнения и риски согласно системным инструкциям."));

        for (MultipartFile image : images) {
            try {
                String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());

                String contentType = image.getContentType();
                if (contentType == null || contentType.isBlank() || contentType.equalsIgnoreCase("application/octet-stream")) {
                    contentType = "image/jpeg";
                }

                String dataUrl = "data:" + contentType + ";base64," + base64Image;
                userContents.add(new ImageUrlContent("image_url", new ImageUrlInfo(dataUrl)));

                log.info("Изображение успешно закодировано с типом контента: {}", contentType);
            } catch (Exception e) {
                log.error("Критическая ошибка кодирования изображения для арендатора: {}", metadata.tenantId(), e);
                throw new RuntimeException("Ошибка конвертации изображения", e);
            }
        }

        messages.add(new YandexMessage("user", userContents));
        YandexChatRequest request = new YandexChatRequest(dynamicModelUri, messages, 0.3);

        log.info("Отправка калиброванного мультимодального запроса в Yandex DeepSeek V4.1 Flash для арендатора: {}", metadata.tenantId());

        Map<String, Object> response = yandexRestClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);

        return parseResponse(response);
    }

    private void validateImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty() || images.size() > 3) {
            throw new IllegalArgumentException("Количество фотографий груза должно быть от 1 до 3");
        }
    }

    private CargoAnalysisResponse parseResponse(Map<String, Object> response) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Отсутствует массив choices в ответе Яндекса");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String fullText = (String) message.get("content");

            if (fullText == null || fullText.isBlank()) {
                throw new RuntimeException("Отсутствует контент ответа нейросети");
            }

            String jsonStr = extractJsonBlock(fullText);
            Map<String, Object> jsonMap = objectMapper.readValue(jsonStr, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            Map<String, Object> dimsMap = (Map<String, Object>) jsonMap.get("single_unit_dimensions_m");
            Dimensions dimensions = new Dimensions(
                    ((Number) dimsMap.get("length")).doubleValue(),
                    ((Number) dimsMap.get("width")).doubleValue(),
                    ((Number) dimsMap.get("height")).doubleValue()
            );

            return new CargoAnalysisResponse(
                    UUID.randomUUID(),
                    (String) jsonMap.get("cargo_type"),
                    (String) jsonMap.get("packaging"),
                    (String) jsonMap.get("reference_object_found"),
                    dimensions,
                    ((Number) jsonMap.get("total_estimated_volume_m3")).doubleValue(),
                    (String) jsonMap.get("estimated_weight_range_kg"),
                    (String) jsonMap.get("recommended_vehicle"),
                    (String) jsonMap.get("logistic_notes")
            );

        } catch (JsonProcessingException e) {
            throw new RuntimeException("Ошибка парсинга JSON: " + e.getMessage(), e);
        }
    }

    private String extractJsonBlock(String text) {
        if (text == null || text.isBlank()) {
            throw new RuntimeException("Входной текст для извлечения JSON пуст");
        }

        int start = -1;
        int bracketCount = 0;

        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{') {
                if (start == -1) {
                    start = i;
                }
                bracketCount++;
            } else if (ch == '}') {
                if (start != -1) {
                    bracketCount--;
                    if (bracketCount == 0) {
                        return text.substring(start, i + 1);
                    }
                }
            }
        }

        int fallbackStart = text.indexOf("{");
        int fallbackEnd = text.lastIndexOf("}");
        if (fallbackStart != -1 && fallbackEnd != -1 && fallbackEnd > fallbackStart) {
            return text.substring(fallbackStart, fallbackEnd + 1);
        }

        throw new RuntimeException("В ответе нейросети не обнаружен валидный JSON-блок. Исходный текст: " + text);
    }
}
