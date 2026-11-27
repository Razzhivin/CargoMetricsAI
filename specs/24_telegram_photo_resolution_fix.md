# Спецификация Итерации №2: Патч №24 — Извлечение только максимального разрешения из `message.getPhoto()`

## 1. Контекст и Проблема
Telegram Bot API в поле `message.getPhoto()` возвращает список объектов `PhotoSize`, представляющих **одно и то же изображение в 3–4 разных разрешениях** (от миниатюры 90px до оригинала 1280px). В текущей реализации бот скачивает все элементы списка `message.getPhoto()`, из-за чего даже при отправке 1 фотографии в `CargoAnalysisService` передается 4 файла и срабатывает валидация `Количество фотографий груза должно быть от 1 до 3`.

## 2. Изменения в `org.cargometricsai.bot.CargoMetricsTelegramBot`
1. Вспомогательный метод извлечения `fileId` из `Message` должен выбирать **строго один `PhotoSize` с максимальным размером файла (`getFileSize`) или шириной (`getWidth`)**:

```java
private Optional<String> extractBestPhotoFileId(Message message) {
    if (message.hasPhoto() && !message.getPhoto().isEmpty()) {
        return message.getPhoto().stream()
                .max(Comparator.comparingInt(p -> p.getFileSize() != null ? p.getFileSize() : p.getWidth()))
                .map(PhotoSize::getFileId);
    }
    if (message.hasDocument() && message.getDocument().getMimeType() != null
            && message.getDocument().getMimeType().startsWith("image/")) {
        return Optional.of(message.getDocument().getFileId());
    }
    return Optional.empty();
}
```

2. Использовать `extractBestPhotoFileId(message)` как при обработке одиночного фото, так и при добавлении кадра в буфер медиаальбома (`mediaGroupId`).
3. Дополнительно в `CargoMetricsTelegramBot` перед передачей списка `fileIds` в `processFileIds` гарантировать обрезку списка до 3 элементов (`fileIds.stream().limit(3).toList()`), если пользователь отправил в альбоме 4 и более разных фотографий.
4. Не логировать полный URL с `botToken` в консоль (`log.info("Скачивание файла из Telegram: path={}", filePath)` вместо вывода полного URL с токеном).

## 3. Критерии приемки (DoD)
1. При отправке 1 фотографии скачивается только 1 файл максимального разрешения (`size ~ 224 KB`).
2. При отправке альбома из 2–3 фотографий скачивается ровно 2–3 файла максимального разрешения.
3. Успешно выполняется запрос к Yandex DeepSeek V4.1 Flash, и в Telegram приходит готовая карточка расчета.
