# Specification: 00-core-yandex-integration.md

## 📌 Описание итерации
**Цель:** Создание минимально рабочего MVP-приложения проекта **CargoMetricsAI** для автоматического распознавания весогабаритных характеристик (ВГХ) грузов по фотографиям с использованием технологий Яндекс. 
**Способ проверки:** Сквозное тестирование через Postman (`multipart/form-data`) с имитацией входящих каналов (Telegram, Макс).

---

## 🛠️ Технологический стек и конфигурация
- **Язык:** Java 21 (Records, Виртуальные потоки)
- **Фреймворк:** Spring Boot 4.x (`spring-boot-starter-web`)
- **Сборщик:** Apache Maven
- **Базовый пакет:** `org.cargometricsai`

### `src/main/resources/application.yaml`
```yaml
spring:
  application:
    name: cargo-metrics-ai
  threads:
    virtual:
      enabled: true
  servlet:
    multipart:
      max-file-size: 10MB
      max-request-size: 30MB

yandex:
  api:
    token: ${YANDEX_API_TOKEN:mock-token}
    folder-id: ${YANDEX_FOLDER_ID:mock-folder}
```

---

## 📐 Архитектура и Структура Пакетов
```
org.cargometricsai
 ├── config
 │    └── YandexCloudConfig.java
 ├── controller
 │    ├── CargoAnalysisController.java
 │    └── GlobalExceptionHandler.java
 ├── domain
 │    └── dto
 │         ├── Dimensions.java
 │         ├── RequestMetadata.java
 │         ├── CargoAnalysisResponse.java
 │         └── yandex
 │              ├── YandexChatRequest.java
 │              ├── CompletionOptions.java
 │              ├── YandexMessage.java
 │              ├── ImageContent.java
 │              └── TextContent.java
 └── service
      ├── CargoAnalysisService.java
      └── CargoAnalysisServiceImpl.java
```

---

## 📄 Контракты Данных (Java 21 Records)

### `org.cargometricsai.domain.dto.Dimensions.java`
```java
package org.cargometricsai.domain.dto;

public record Dimensions(double length, double width, double height) {}
```

### `org.cargometricsai.domain.dto.RequestMetadata.java`
```java
package org.cargometricsai.domain.dto;

public record RequestMetadata(
    String tenantId,
    String sourceChannel,
    String externalUserId
) {}
```

### `org.cargometricsai.domain.dto.CargoAnalysisResponse.java`
```java
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
```

### `org.cargometricsai.domain.dto.yandex` (Модели для API Яндекса)
```java
package org.cargometricsai.domain.dto.yandex;
import java.util.List;

public record YandexChatRequest(String modelUri, CompletionOptions completionOptions, List<YandexMessage> messages) {}
public record CompletionOptions(boolean stream, double temperature, int maxTokens) {}
public record YandexMessage(String role, Object content) {}
public record ImageContent(String type, String image_bytes) {}
public record TextContent(String type, String text) {}
```

---

## ⚙️ Системный Промпт для Яндекс ИИ
```text
Ты — профессиональный ИИ-логист. Твоя задача — проанализировать фото, классифицировать груз, оценить его габариты и ПРИМЕРНЫЙ ВЕС, исходя из плотности материала.

Выполняй анализ по шагам:
1. КЛАССИФИКАЦИЯ: Что на фото? Каков материал (металл, дерево, пластик, текстиль) и тип упаковки.
2. ОЦЕНКА МАСШТАБА: Найди объект-референс (лист А4, поддон, дверь, плитка на полу) и укажи его.
3. ГАБАРИТЫ И ВЕС (Важно):
   - Оцени размеры одной единицы груза (Д х Ш х В в метрах).
   - Оцени общий объем всей партии на фото (м³).
   - Оцени примерный физический вес (в кг), исходя из объема и удельного веса материала. Укажи диапазон веса (от/до).
4. ТРАНСПОРТ: Подбери оптимальный автомобиль, учитывая не только сухой объем и вес, но и ГЕОМЕТРИЧЕСКУЮ СЛОЖНОСТЬ груза (высоту, ширину, неразборность конструкции).
   - Если груз неделимый, высокий или имеет торчащие жесткие элементы — ИСКЛЮЧАЙ малые легковые фургоны (Ларгус/каблук) и сразу рекомендуй Газель (малотоннажный грузовик с высотой тента/будки от 1.8м) или открытый борт.

Выдай ответ строго в формате JSON:
{
  "cargo_type": "тип груза и материал",
  "packaging": "тип упаковки",
  "reference_object_found": "маркер масштаба",
  "single_unit_dimensions_m": {"length": 0.00, "width": 0.00, "height": 0.00},
  "total_estimated_volume_m3": 0.00,
  "estimated_weight_range_kg": "от 00 до 00",
  "recommended_vehicle": "тип авто по весу и объему",
  "logistic_notes": "короткий комментарий по специфике укладки/закрепления груза"
}
```

---

## 🚀 Бизнес-Логика и Интеграция

### `org.cargometricsai.config.YandexCloudConfig.java`
```java
package org.cargometricsai.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

@Configuration
public class YandexCloudConfig {
    @Bean
    public RestClient yandexRestClient() {
        return RestClient.builder()
                .baseUrl("https://llm.api.cloud.yandex.net")
                .build();
    }
}
```

### `org.cargometricsai.service.CargoAnalysisService.java`
```java
package org.cargometricsai.service;

import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

public interface CargoAnalysisService {
    CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata);
}
```

### Требования к `CargoAnalysisServiceImpl.java`:
1. **Валидация:** Проверять размер коллекции `images`. Если `images == null || images.isEmpty() || images.size() > 3`, выбрасывать `IllegalArgumentException("Количество фотографий груза должно быть от 1 до 3")`.
2. **Кодирование:** Преобразовывать каждый `MultipartFile` в Base64: `java.util.Base64.getEncoder().encodeToString(file.getBytes())`.
3. **Оркестрация Payload:** Формировать `YandexChatRequest`. Системный промпт передавать строкой (`role: "system"`). Изображения маппить в массив объектов `ImageContent` и добавлять вместе с `TextContent("Проанализируй этот груз.")` в `role: "user"`.
4. **Сетевой вызов:** Выполнять POST-запрос к урлу `/foundationModels/v1/completion` с заголовками `Authorization: Bearer <token>` и `x-folder-id: <folder-id>`.
5. **Парсинг:** Извлечь из текстового ответа Яндекса JSON-блок, десериализовать его с помощью Jackson `ObjectMapper` и смаппить в результирующий `CargoAnalysisResponse` с добавлением сгенерированного `UUID.randomUUID()`.

---

## 🌐 Контроллер и Валидация

### `org.cargometricsai.controller.CargoAnalysisController.java`
```java
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
```

### `org.cargometricsai.controller.GlobalExceptionHandler.java`
```java
package org.cargometricsai.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }
}
```