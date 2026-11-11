# Спецификация Итерации №1 (Патч №2): Исправление контракта Yandex Multimodal Payload

## 1. Описание проблемы
При отправке запроса Yandex Cloud API возвращает ошибку 400 Bad Request с сообщением "empty message text". Это вызвано тем, что Jackson сериализует поля `text` и `content` в объекте `YandexMessage`, а также поля в `ImageContent` не совсем так, как ожидает strict-парсер Яндекса. Нам необходимо применить аннотации Jackson для исключения null-полей и точного маппинга JSON-свойств.

## 2. Модификация DTO-слоя (Yandex Models)

Необходимо обновить рекорды в пакете `org.cargometricsai.domain.dto.yandex` следующими аннотациями Jackson:

### YandexMessage.java
Должен поддерживать выборочную сериализацию. Если это роль system, поле `content` должно отсутствовать в JSON. Если роль user — поле `text` должно отсутствовать.
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record YandexMessage(
    @JsonProperty("role") String role,
    @JsonProperty("text") String text,
    @JsonProperty("content") List<Object> content
) {}
```

### ImageContent.java
Поле со строкой Base64 должно называться strictly `image_bytes`.
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageContent(
    @JsonProperty("type") String type, // "image_bytes"
    @JsonProperty("image_bytes") String imageBytes
) {}
```

### TextContent.java
```java
package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record TextContent(
    @JsonProperty("type") String type, // "text"
    @JsonProperty("text") String text
) {}
```

## 3. Обновление фабричных методов в CargoAnalysisServiceImpl.java

Убедись, что при создании сообщений передаются явные `null` для исключаемых из JSON полей:

1. Системное сообщение (System prompt):
```java
YandexMessage systemMessage = new YandexMessage("system", SYSTEM_PROMPT, null);
```

2. Пользовательское сообщение (User content):
```java
List<Object> contentList = new ArrayList<>();
for (MultipartFile image : images) {
    String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());
    contentList.add(new ImageContent("image_bytes", base64Image));
}
contentList.add(new TextContent("text", "Проанализируй этот груз."));

YandexMessage userMessage = new YandexMessage("user", null, contentList);
```

## 🦾 Задание для ИИ-агента в IntelliJ IDEA
Примени указанные изменения к рекордам `YandexMessage`, `ImageContent`, `TextContent` и обнови методы построения payload в `CargoAnalysisServiceImpl`. Убедись, что проект компилируется, а Jackson перестал передавать пустые/null значения в JSON Яндекса.
