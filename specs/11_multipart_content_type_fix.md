# Спецификация Итерации №1: Патч №11 — Нормализация MIME-типов MultipartFile для мультимодального инференса YandexGPT Pro 5

## 1. Контекст и Локализация Ошибки
При E2E-тестировании инференса `yandexgpt-5-pro` приложение выбросило исключение:
`RuntimeException: В ответе нейросети не обнаружен валидный JSON-блок. Исходный текст: Для выполнения анализа мне необходима фотография груза...`

### Причина сбоя:
Шлюз Yandex AI Studio проигнорировал переданные бинарные данные изображений, в результате чего модель получила пустой массив медиа-контента. Это вызвано тем, что Postman при отправке `multipart/form-data` по умолчанию не передал или исказил заголовок `Content-Type` для файла (выставив `application/octet-stream`). Динамическая сборка `image.getContentType()` передала некорректный MIME-тип в Data URL (`data:application/octet-stream;base64,...`), который заблокировал парсинг картинок на стороне Яндекса.

---

## 2. Архитектурное Решение
Обеспечить принудительную нормализацию медиа-заголовков на стороне бэкенда. Если MIME-тип файла не определен или равен `application/octet-stream`, принудительно подставлять стандартный `image/jpeg`.

---

## 3. Изменения в кодовой базе (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

ИИ-агент в IDEA должен обновить цикл кодирования Base64 в методе `analyzeCargoImages`:

```java
// Найти цикл обработки изображений в CargoAnalysisServiceImpl.java и заменить его содержимое:
for (MultipartFile image : images) {
    try {
        String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());
        
        // Критическое исправление: жесткая нормализация MIME-типов для исключения application/octet-stream
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
```

---

## 4. План повторной валидации (E2E)
1. Агент в IDEA обновляет логику нормализации MIME-типов.
2. В интерфейсе **Postman** в параметрах `body` -> `form-data` для загружаемого файла проверить настройки: нажать на скрытые параметры строки (или три точки) и убедиться, что файл отправляется как картинка, либо дождаться перезапуска бэкенда, который принудительно исправит тип на `image/jpeg`.
3. Повторить запрос и верифицировать успешную генерацию JSON-ответа от `yandexgpt-5-pro`.
