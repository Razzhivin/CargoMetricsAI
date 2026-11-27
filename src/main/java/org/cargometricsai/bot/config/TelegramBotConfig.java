package org.cargometricsai.bot.config;

import org.cargometricsai.bot.CargoMetricsTelegramBot;
import okhttp3.OkHttpClient;
import org.cargometricsai.bot.service.TelegramNotificationFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

import java.time.Duration;

@Configuration
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public TelegramClient telegramClient(@Value("${telegram.bot.token}") String botToken) {
        OkHttpClient okHttpClient = new OkHttpClient.Builder()
                .connectTimeout(Duration.ofSeconds(30))
                .readTimeout(Duration.ofSeconds(30))
                .writeTimeout(Duration.ofSeconds(30))
                .retryOnConnectionFailure(true)
                .build();
        return new OkHttpTelegramClient(okHttpClient, botToken);
    }

    @Bean
    public TelegramBotsLongPollingApplication telegramBotsApplication() {
        return new TelegramBotsLongPollingApplication();
    }

    @Bean
    public ApplicationRunner botRegistrar(
            TelegramBotsLongPollingApplication botsApplication,
            CargoMetricsTelegramBot bot,
            @Value("${telegram.bot.token}") String botToken
    ) {
        return args -> Thread.startVirtualThread(() -> {
            int maxRetries = 5;
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    botsApplication.registerBot(botToken, bot);
                    log.info("=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===");
                    return;
                } catch (Exception e) {
                    log.warn("Попытка {}/{} регистрации Telegram-бота не удалась: {}. Повтор через 15 сек...",
                            attempt, maxRetries, e.getMessage());
                    try {
                        Thread.sleep(15_000);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
            log.error("=== НЕ УДАЛОСЬ ЗАРЕГИСТРИРОВАТЬ TELEGRAM БОТА ПОСЛЕ {} ПОПЫТОК ===", maxRetries);
        });
    }
}
