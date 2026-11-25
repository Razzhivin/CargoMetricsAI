# Спецификация Итерации №2: Патч №22 — Корректное получение `filePath` через `GetFile`, буферизация альбомов и маскирование ошибок

## 1. Контекст и Проблемы
1. **Ошибка `FileNotFoundException` при скачивании:** Метод `downloadImage` подставляет `PhotoSize.getFileId()` напрямую в URL `https://api.telegram.org/file/bot<token>/<filePath>`. В Telegram Bot API необходимо сначала выполнить запрос `GetFile(fileId)` через `telegramClient` и использовать полученный `file.getFilePath()` (либо встроенный метод `telegramClient.downloadFileAsStream(file)`).
2. **Утечка `BOT_TOKEN` в сообщения об ошибках:** При исключении в чат отправляется `e.getMessage()`, содержащий URL с токеном бота. Необходимо отправлять пользователю безопасное сообщение без технических деталей.
3. **Отсутствие склейки медиаальбомов (`mediaGroupId`):** При отправке 2–3 фото одним альбомом Telegram присылает несколько последовательных `Update` с одинаковым `message.getMediaGroupId()`. Сейчас каждый кадр обрабатывается отдельно.
4. **Символы `%n` в текстах сообщений:** Заменить все вхождения `%n` в строковых шаблонах `TelegramNotificationFormatter` и `CargoMetricsTelegramBot` на стандартный перенос строки `\n`.

---

## 2. Изменения в кодовой базе

### 2.1. Исправление скачивания файла (`CargoMetricsTelegramBot.java`)
Заменить ручную сборку URL в методе `downloadImage(String fileId)` на официальный вызов `GetFile` и `downloadFileAsStream`:

```java
private InMemoryMultipartFile downloadImage(String fileId, int index) throws Exception {
    GetFile getFileRequest = GetFile.builder()
            .fileId(fileId)
            .build();
    org.telegram.telegrambots.meta.api.objects.File telegramFile = telegramClient.execute(getFileRequest);

    try (InputStream is = telegramClient.downloadFileAsStream(telegramFile)) {
        byte[] bytes = is.readAllBytes();
        return new InMemoryMultipartFile(
                "images",
                "cargo_" + index + ".jpg",
                "image/jpeg",
                bytes
        );
    }
}
```

### 2.2. Буферизация медиаальбомов (`mediaGroupId`)
В `CargoMetricsTelegramBot`:
1. Добавить потокобезопасный буфер `private final ConcurrentHashMap<String, List<String>> mediaGroupBuffer = new ConcurrentHashMap<>();` и планировщик `private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();`.
2. Если `message.getMediaGroupId() != null`:
    * Извлечь `fileId` фото максимального размера (или документа-изображения).
    * Если `mediaGroupBuffer.putIfAbsent(mediaGroupId, Collections.synchronizedList(new ArrayList<>())) == null` (первое фото альбома):
        * Добавить `fileId` в список.
        * Запланировать через `scheduler.schedule(..., 1500, TimeUnit.MILLISECONDS)` извлечение списка из `mediaGroupBuffer.remove(mediaGroupId)` (ограничив до первых 3 фото) и передачу их в `processFileIds(chatId, username, fileIds)`.
    * Иначе (последующие фото того же альбома): просто добавить `fileId` в существующий список в буфере и завершить обработку текущего `Update`.

### 2.3. Безопасная обработка ошибок и очистка `%n`
1. В блоке `catch (Exception e)` при обработке фото отправлять пользователю только безопасный текст:
   `"⚠️ Не удалось обработать фотографии. Пожалуйста, убедитесь, что отправляете четкие изображения (до 3 штук), и попробуйте еще раз."`
2. Заменить все `%n` в текстовых константах и методах форматирования на `\n`.

---

## 3. Критерии приемки (DoD)
1. В приветствии `/start` отображаются корректные переносы строк без символов `%n`.
2. При отправке альбома из 2 фотографий бетономешалки бот ждет 1.5 секунды, объединяет оба снимка в один вызов `CargoAnalysisService` и возвращает единую карточку логиста.
3. Текст ошибок в чате не содержит URL с токеном бота.
