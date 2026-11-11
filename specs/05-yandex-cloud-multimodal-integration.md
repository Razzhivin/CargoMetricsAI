# Спецификация Итерации №1: Патч №5 — Мультимодальная интеграция с Yandex AI Studio через OpenAI-совместимый API

## 1. Контекст и Технические Ограничения
В процессе E2E-тестирования MVP CargoMetricsAI для распознавания характеристик грузов по фотографиям были локализованы критические ограничения платформы Yandex Cloud:
1. **Текстовые ограничения YandexGPT:** Модели семейства `yandexgpt-5-pro` / `yandexgpt/latest` являются исключительно текстовыми. Передача структуры `image_url` вызывает ошибку `400 Bad Request`.
2. **Специфика контракта OpenAI в Яндексе:** Поле `model` требует полный системный URI с префиксом протокола и каталогом (`gpt://<folder_id>/<model_name>`). Короткие алиасы вроде `yandexgpt-vision` шлюзом не поддерживаются.
3. **Мультимодальное решение:** Для анализа изображений в Yandex AI Studio используется партнерская мультимодальная модель `qwen3.6-35b-a3b`, полностью поддерживающая стандарты Computer Vision.

---

## 2. Целевая Архитектура и DTO-слой (Java 21 / Spring Boot 4.x)

Для исключения ошибок валидации JSON-схемы Яндекса, DTO-слой сообщений переводится на строгий OpenAI-контракт. Из `YandexMessage` удалено дублирующее текстовое поле, а `content` объявлен полиморфным объектом.

### 2.1. Рекорд `YandexMessage.java` (`org.cargometricsai.domain.dto.yandex`)
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YandexMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") Object content // String для роли system, List<Object> для роли user
) {}
```

### 2.2. Рекорд `TextContent.java` (`org.cargometricsai.domain.dto.yandex`)
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TextContent(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text
) {}
```

---

## 3. Логика Сервиса (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

Реализация переведена на стратегию **«Prompt-First»** (текст идет первым элементом в массиве `content`) и использует мультимодальную модель `qwen3.6-35b-a3b`.

```java
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
            Ты — профессиональный ИИ-логист. Твоя задача — проанализировать фото, классифицировать груз, оценить его габариты и ПРИМЕРНЫЙ ВЕС, исходя из плотности материала.
            Выдай ответ строго в формате JSON без markdown-разметки:
            {
              "cargo_type": "тип груза и материал",
              "packaging": "тип упаковки",
              "reference_object_found": "маркер масштаба",
              "single_unit_dimensions_m": {"length": 0.00, "width": 0.00, "height": 0.00},
              "total_estimated_volume_m3": 0.00,
              "estimated_weight_range_kg": "от 00 до 00",
              "recommended_vehicle": "тип авто по весу и объему",
              "logistic_notes": "короткий комментарий"
            }
            """;

    @Value("\${yandex.api.folder-id}")
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

        // Полный верифицированный URI мультимодальной модели согласно Yandex AI Studio
        String dynamicModelUri = "gpt://" + folderId + "/qwen3.6-35b-a3b"; 

        List<YandexMessage> messages = new ArrayList<>();
        messages.add(new YandexMessage("system", SYSTEM_PROMPT));

        List<Object> userContents = new ArrayList<>();
        // Стратегия Prompt-First: Инструкция обязана идти первой перед изображениями
        userContents.add(new TextContent("text", "Проанализируй этот груз по фотографии, определи габариты и вес согласно системным инструкциям."));

        for (MultipartFile image : images) {
            try {
                String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());
                String contentType = image.getContentType() != null ? image.getContentType() : "image/jpeg";
                String dataUrl = "data:" + contentType + ";base64," + base64Image;

                userContents.add(new ImageUrlContent("image_url", new ImageUrlInfo(dataUrl)));
            } catch (Exception e) {
                log.error("Критическая ошибка кодирования изображения для арендатора: {}", metadata.tenantId(), e);
                throw new RuntimeException("Ошибка конвертации изображения", e);
            }
        }
        
        messages.add(new YandexMessage("user", userContents));
        YandexChatRequest request = new YandexChatRequest(dynamicModelUri, messages, 0.3);

        log.info("Отправка OpenAI-совместимого запроса в Yandex Vision API для арендатора: {}", metadata.tenantId());

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
        int start = text.indexOf("{");
        int end = text.lastIndexOf("}");
        if (start != -1 && end != -1 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }
}
```

---

## 4. План Верификации и Definition of Done
1. **Применение спеки агентом:** ИИ-агент (или разработчик) обновляет `YandexMessage.java`, создает `TextContent.java` и полностью перезаписывает тело `CargoAnalysisServiceImpl.java`.
2. **Локальный инференс:** Запуск приложения с `spring.threads.virtual.enabled=true`.
3. **E2E тест через Postman:** `POST /api/v1/cargo/analyze`, передача 1 файла в `multipart/form-data`.
4. **Успешный критерий:** HTTP-статус `200 OK`, маппинг в Java Record прошел без `JsonProcessingException`.
