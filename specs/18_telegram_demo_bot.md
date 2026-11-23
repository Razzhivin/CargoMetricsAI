# Спецификация Итерации №2: Патч №18 — Внедрение Telegram-бота для самостоятельного тестирования (Trial-воронка)

## 1. Контекст и Бизнес-Цели
Для масштабирования B2B-продаж и автоматизации первичного охвата (рассылки по базам ТК) требуется публичная точка входа, позволяющая логистам самостоятельно протестировать сервис на своих фотографиях без ручного проведения демо-созвонов.

**Ключевые бизнес-требования:**
1. **Нулевой барьер входа:** Пользователь отправляет или пересылает 1–3 фотографии груза в чат и через несколько секунд получает отформатированный расчет.
2. **Поддержка медиаальбомов:** Корректная склейка нескольких фото, отправленных одним альбомом (`media_group_id`), в единый вызов `CargoAnalysisService`.
3. **Защита от перерасхода токенов (Trial-лимит):** Ограничение в **3 бесплатных анализа** на один `chat_id`. По исчерпании лимита выдается оффер на подключение компании и прямые контакты для связи.
4. **Трекинг лидов:** Отправка уведомления администратору (`ADMIN_CHAT_ID`) при каждом успешном анализе нового пользователя.

---

## 2. Зависимости и Конфигурация

### 2.1. Зависимости `pom.xml`
Добавить официальный стартер Telegram Bots для Spring Boot (актуальная версия с поддержкой Long Polling и Virtual Threads):

```xml
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-springboot-longpolling-starter</artifactId>
    <version>7.10.0</version>
</dependency>
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-client</artifactId>
    <version>7.10.0</version>
</dependency>
```

### 2.2. Переменные окружения (`.env` и `application.yml`)
Добавить в `.env` и `.env.example`:
```env
TELEGRAM_BOT_TOKEN=your_bot_token_from_botfather
TELEGRAM_BOT_USERNAME=CargoMetricsAIBot
TELEGRAM_ADMIN_CHAT_ID=your_personal_telegram_id
```

Добавить в `src/main/resources/application.yml`:
```yaml
telegram:
  bot:
    token: \${TELEGRAM_BOT_TOKEN}
    username: \${TELEGRAM_BOT_USERNAME}
    admin-chat-id: \${TELEGRAM_ADMIN_CHAT_ID}
    free-trial-limit: 3
```

---

## 3. Архитектура компонентов (`org.cargometricsai.bot`)

Создать пакет `org.cargometricsai.bot` со следующей структурой классов:

1. **`InMemoryMultipartFile.java`** (в пакете `org.cargometricsai.bot.util`):
    * Реализация интерфейса `org.springframework.web.multipart.MultipartFile` для оборачивания `byte[]` скачанных из Telegram фотографий без сохранения временных файлов на диск.
2. **`TrialRateLimiterService.java`** (в пакете `org.cargometricsai.bot.service`):
    * Потокобезопасный сервис (`ConcurrentHashMap<Long, AtomicInteger>`), отслеживающий количество использованных запросов по `chatId`.
    * Методы: `boolean canProcess(Long chatId)`, `int incrementAndGet(Long chatId)`, `int getRemaining(Long chatId)`.
    * Для `ADMIN_CHAT_ID` лимит не применяется (безлимитный доступ для проведения демо).
3. **`TelegramNotificationFormatter.java`** (в пакете `org.cargometricsai.bot.service`):
    * Формирует два типа сообщений из `CargoAnalysisResponse`:
        * **Карточка логиста (HTML-разметка Telegram):** структурированный отчет с габаритами, объемом, предполагаемым весом, рекомендуемым авто, рисками и остатком бесплатных попыток.
        * **Шаблон для ATI.SU:** лаконичный текст без лишнего форматирования для копирования в карточку груза на бирже (выдается по нажатию на Inline-кнопку `📋 Текст для ATI.SU`).
4. **`CargoMetricsTelegramBot.java`** (в пакете `org.cargometricsai.bot`):
    * Реализует `SpringLongPollingBot` и `LongPollingSingleThreadUpdateConsumer`.
    * **Обработка `/start`:** Приветствие, краткая инструкция («Отправьте или перешлите 1–3 фото груза») и информация о 3 бесплатных тестах.
    * **Буферизация альбомов (`mediaGroupId`):** Если сообщение содержит `mediaGroupId`, накапливает `PhotoSize` (максимального разрешения) в `ConcurrentHashMap<String, List<PhotoSize>>` и запускает отложенную задачу через `ScheduledExecutorService` (задержка 1500 мс) на виртуальном потоке для пакетной обработки до 3 фото за один запрос.
    * **Скачивание файлов:** Получение `FilePath` через `GetFile`, скачивание байтов по `https://telegram.org<token>/<file_path>`, оборачивание в `InMemoryMultipartFile` (с типом `image/jpeg`) и вызов `cargoAnalysisService.analyzeCargoImages(files, metadata)`.
    * **Inline-клавиатура под ответом:**
        * Кнопка `📋 Текст для ATI.SU` (кэширует последний ответ по `requestId` в `ConcurrentHashMap<String, CargoAnalysisResponse>` и отправляет чистый текст при клике).
        * Кнопка `🚀 Подключить компанию` (ссылка `https://t.me` или ваш прямой `@username`).
    * **Уведомление администратору:** После успешного анализа отправляет в `adminChatId` сообщение: `🔔 Новый тест в боте! Пользователь: @username (ID: 12345), Груз: [cargoType], Машина: [recommendedVehicle]`.

---

## 4. Критерии приемки (DoD)
1. Бот успешно запускается в контексте Spring Boot 4.x и реагирует на команду `/start`.
2. Отправка 1 фото или пересылка медиаальбома из 2–3 фото одним сообщением приводит к **одному** вызову `CargoAnalysisService` и выдаче красивой карточки расчета.
3. На 4-м запросе от обычного пользователя срабатывает блокировка триала с предложением связаться для подключения отдела логистики.
4. Нажатие на кнопку `📋 Текст для ATI.SU` выдает готовое описание груза для вставки на биржу.
5. Администратор получает уведомление о каждом новом прогоне в личный чат.
