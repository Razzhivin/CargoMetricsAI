# Спецификация Итерации №2: Патч №20 — Замена устаревшей зависимости `telegrambots:6.9.7.1` на актуальную `7.10.0`

## 1. Контекст и Проблема
В classpath проекта обнаружена устаревшая библиотека `org.telegram:telegrambots:6.9.7.1` (использующая старый API `DefaultBotSession` и `javax.xml.bind`), которая не совместима с конфигурацией `TelegramBotsLongPollingApplication` (API 7.x+) и Spring Boot 4.0.1.

---

## 2. Изменения в `pom.xml`

1. **Удалить** любые зависимости `org.telegram:telegrambots` и `org.telegram:telegrambots-spring-boot-starter` версии `6.x`.
2. **Добавить** актуальные модули ветки `7.10.0` (без spring-boot-starter, так как регистрация выполняется явно через `ApplicationRunner`):

```xml
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-longpolling</artifactId>
    <version>7.10.0</version>
</dependency>
<dependency>
    <groupId>org.telegram</groupId>
    <artifactId>telegrambots-client</artifactId>
    <version>7.10.0</version>
</dependency>
```

---

## 3. Изменения в кодовой базе (`org.cargometricsai.bot`)

### 3.1. `CargoMetricsTelegramBot.java`
Убрать реализацию `SpringLongPollingBot` (так как стартер удален) и реализовать напрямую интерфейс **`org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer`**:

```java
@Slf4j
@Component
public class CargoMetricsTelegramBot implements LongPollingSingleThreadUpdateConsumer {
    // ... существующая логика consume(Update update) ...
}
```

### 3.2. `TelegramBotConfig.java`
Обновить регистрацию бота в `ApplicationRunner`, передавая токен из `@Value("${telegram.bot.token}")` и сам бин `CargoMetricsTelegramBot`:

```java
package org.cargometricsai.bot.config;

import lombok.extern.slf4j.Slf4j;
import org.cargometricsai.bot.CargoMetricsTelegramBot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
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
            CargoMetricsTelegramBot bot,
            @Value("\${telegram.bot.token}") String botToken
    ) {
        return args -> {
            try {
                botsApplication.registerBot(botToken, bot);
                log.info("=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===");
            } catch (Exception e) {
                log.error("=== ОШИБКА РЕГИСТРАЦИИ TELEGRAM БОТА ===", e);
            }
        };
    }
}
```

---

## 4. Критерии приемки (DoD)
1. В `pom.xml` отсутствуют артефакты `telegrambots` версии `6.x`, выполнен Maven Reload.
2. При старте приложения в логах выводится `=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===`.
3. Бот отвечает на `/start` и обрабатывает отправленные фотографии.
