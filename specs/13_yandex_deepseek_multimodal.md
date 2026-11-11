# Спецификация Итерации №1: Патч №13 — Переход на мультимодальное ядро DeepSeek V4.1 Flash внутри контура Yandex AI Studio

## 1. Контекст и Актуализация Данных
На основе данных консоли Yandex AI Studio от 09.11.2026 верифицировано появление адаптированной партнерской мультимодальной модели `deepseek-v4.1-flash/latest` (класс задачи: `image-text-to-text`). Модель обладает продвинутым мультимодальным зрением и высоким уровнем логических рассуждений, превосходящим базовые бенчмарки.

**Решение:** Для достижения максимальной точности MVP CargoMetricsAI без выхода из безопасного контура Yandex Cloud, перевести инференс на внутренний мультимодальный инстанс DeepSeek V4.1 Flash.

---

## 2. Архитектурное Решение
1. **Идентификатор модели:** Использовать полный URI формата `gpt://<folder_id>/deepseek-v4.1-flash/latest`.
2. **Сохранение инфраструктуры:** Полная совместимость с ранее внедренными DTO-моделями, Base64 нормализацией и стек-парсер JSON-экстрактором.

---

## 3. Изменения в кодовой базе (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

ИИ-агент в IDEA должен обновить только строку инициализации URI модели:

```java
@Override
public CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata) {
    validateImages(images);

    // Целевой URI мультимодального DeepSeek внутри Yandex Cloud (Обновление от ноября 2026)
    String dynamicModelUri = "gpt://" + folderId + "/deepseek-v4.1-flash/latest"; 

    List<YandexMessage> messages = new ArrayList<>();
    messages.add(new YandexMessage("system", SYSTEM_PROMPT)); // Промпт с калибровкой грязи, стекол и веса полостей

    List<Object> userContents = new ArrayList<>();
    userContents.add(new TextContent("text", "Проанализируй этот груз по фотографии, определи габариты, вес и логистические риски согласно системным инструкциям."));

    // Логика конвертации Base64 с нормализацией image/jpeg остается прежней...
    for (MultipartFile image : images) {
        try {
            String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());
            String contentType = image.getContentType();
            if (contentType == null || contentType.isBlank() || contentType.equalsIgnoreCase("application/octet-stream")) {
                contentType = "image/jpeg"; 
            }
            String dataUrl = "data:" + contentType + ";base64," + base64Image;
            userContents.add(new ImageUrlContent("image_url", new ImageUrlInfo(dataUrl)));
        } catch (Exception e) {
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
```

---

## 4. План Валидации (DoD)
1. Установить в коде `deepseek-v4.1-flash/latest`.
2. Скомпилировать и перезапустить приложение.
3. Выполнить E2E-тест в Postman. Ожидается идеальное извлечение JSON без падения парсера скобок.
