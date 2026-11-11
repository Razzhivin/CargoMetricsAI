# Спецификация Итерации №1: Патч №6 — Мультимодальная интеграция с Yandex Vision API

## 1. Контекст и Проблема
В процессе сквозного E2E-тестирования распознавания весогабаритных характеристик грузов по фотографиям были выявлены критические несоответствия между текущей реализацией DTO-слоя и требованиями OpenAI-совместимого эндпоинта Yandex Cloud (`/chat/completions`).

### Выявленные уязвимости:
1. **Неверный идентификатор модели:** Использование `yandexgpt/latest` приводило к ошибке `400 Bad Request`, так как базовая текстовая модель не поддерживает обработку массивов изображений.
2. **Некорректная структура JSON-схемы:** Наличие одновременно полей `text` и `content` в рекорде `YandexMessage` вызывало сбой валидации на стороне Яндекса. Для роли `system` ожидается строго текстовое поле `content`, а для роли `user` — полиморфный массив объектов.
3. **Нарушение порядка следования контента:** Размещение текстовой инструкции после массива Base64-изображений снижало качество инференса модели (эффект «слепоты» к промпту).

---

## 2. Архитектурные Решения

1. **Миграция на Vision-модель:** Переключить инференс на специализированную мультимодальную модель `yandexgpt-3-vision/latest`.
2. **Полиморфный DTO-слой сообщений:** Рефакторинг `YandexMessage` с переводом поля `content` на тип `Object` для поддержки как `String` (инструкции), так и `List<Object>` (мультимедиа). Полное удаление дублирующего поля `text`.
3. **Принцип «Prompt-First» в контенте:** Обеспечить строгое позиционирование текстового промпта в качестве **первого** элемента массива `content` перед блоками `image_url`.

---

## 3. Изменения в кодовой базе (Стек: Java 21 / Spring Boot 4.x)

### 3.1. Обновление DTO-моделей (`org.cargometricsai.domain.dto.yandex`)

#### Рекорд `YandexMessage.java`
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YandexMessage(
        @JsonProperty("role") String role,
        @JsonProperty("content") Object content // Строка для system, List<Object> для user
) {}
```

#### Рекорд `TextContent.java`
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TextContent(
        @JsonProperty("type") String type,
        @JsonProperty("text") String text
) {}
```

### 3.2. Обновление логики сервиса (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

```java
@Override
public CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata) {
    validateImages(images);

    // Переключение на мультимодальное ядро Yandex Vision
    String dynamicModelUri = "gpt://" + folderId + "/yandexgpt-3-vision/latest"; 

    List<YandexMessage> messages = new ArrayList<>();
    
    // Передача системного промпта в виде строки
    messages.add(new YandexMessage("system", SYSTEM_PROMPT));

    List<Object> userContents = new ArrayList<>();
    
    // Стратегия Prompt-First: Текст инструкции ВСЕГДА идет первым
    userContents.add(new TextContent("text", "Проанализируй этот груз по фотографии, определи габариты и вес согласно системным инструкциям."));

    // Безопасное кодирование бинарных данных в Base64 Data URL
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
    
    // Передача пользовательского контента в виде структурированного массива
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
```

---

## 4. План валидации (E2E Тестирование)

Тестирование проводится методом отправки запроса в формате `multipart/form-data` через Postman на локальный эндпоинт бэкенда: `POST http://localhost:8080/api/v1/cargo/analyze`.

### Критерии успешности (Definition of Done):
1. HTTP-статус ответа от бэкенда: `200 OK`.
2. В логах приложения отсутствует ошибка `400 Bad Request` от удаленного хоста Яндекса.
3. Поля `total_estimated_volume_m3` и `single_unit_dimensions_m` успешно распарсились Jackson и переведены в типы `double` / `Record` без генерации `NullPointerException`.
