# Спецификация Итерации №2: Патч №19 — Гарантированная регистрация LongPolling-бота в Spring Boot 4.x и расширение приема изображений

## 1. Контекст и Проблема
После запуска приложения в Spring Boot 4.x входящие сообщения в Telegram не обрабатываются, а логи в консоли отсутствуют. Возможные причины:
1. Не срабатывает автоконфигурация `TelegramBotInitializer` из `telegrambots-springboot-longpolling-starter:7.10.0` в среде Spring Boot 4.x.
2. Изображения, отправленные с десктопа без сжатия, приходят как `Document` (`image/*`), а не как `PhotoSize`.
3. Отсутствует логирование факта регистрации бота и поступления сырых `Update`.

---

## 2. Архитектурное Решение

### 2.1. Явная инициализация `TelegramBotsLongPollingApplication` (`TelegramBotConfig.java`)
Создать конфигурационный класс `org.cargometricsai.bot.config.TelegramBotConfig`, который при старте контекста гарантированно регистрирует бин `SpringLongPollingBot` в `TelegramBotsLongPollingApplication` (с проверкой, чтобы избежать двойной регистрации) и выводит в консоль статус подключения:

```java
package org.cargometricsai.bot.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.longpolling.starter.SpringLongPollingBot;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Slf4j
@Configuration
public class TelegramBotConfig {

    @Bean
    public TelegramClient telegramClient(@Value("\${telegram.bot.token}") String botToken) {
        return new OkHttpTelegramClient(botToken);
    }

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    public ApplicationRunner botRegistrar(
            TelegramBotsLongPollingApplication botsApplication,
            SpringLongPollingBot bot
    ) {
        return args -> {
            try {
                botsApplication.registerBot(bot.getBotToken(), bot.getUpdatesConsumer());
                log.info("=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===");
            } catch (Exception e) {
                if (e.getMessage() != null && e.getMessage().contains("already registered")) {
                    log.info("=== TELEGRAM БОТ УЖЕ ЗАРЕГИСТРИРОВАН АВТОКОНФИГУРАЦИЕЙ ===");
                } else {
                    log.error("=== ОШИБКА РЕГИСТРАЦИИ TELEGRAM БОТА ===", e);
                }
            }
        };
    }
}
```

### 2.2. Диагностическое логирование и поддержка фото-файлов (`CargoMetricsTelegramBot.java`)
Обновить обработчик `consume(Update update)` в `CargoMetricsTelegramBot`:
1. На самой первой строке метода добавить `log.info("Входящий Update из Telegram: updateId={}", update.getUpdateId());`.
2. Если пришло сообщение с `Document` (где `mimeType` начинается с `"image/"`), извлекать `fileId` документа и обрабатывать его наравне с обычными `PhotoSize`.
3. Если пользователь отправил неподдерживаемый тип сообщения (простой текст не `/start`, стикер, голосовое), отвечать вежливой подсказкой: *"Пожалуйста, отправьте или перешлите 1–3 фотографии груза (сжатым фото или файлом), чтобы я рассчитал габариты и риски."* — бот никогда не должен молчать.

---

## 3. Критерии приемки (DoD)
1. При запуске приложения в консоли появляется строка `=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===`.
2. При отправке `/start` или фотографии в консоль пишется `Входящий Update из Telegram...`, а бот отвечает в чат.
3. Поддерживаются как сжатые фото (`Photo`), так и картинки, отправленные с ПК файлом (`Document` с `image/jpeg` или `image/png`).
