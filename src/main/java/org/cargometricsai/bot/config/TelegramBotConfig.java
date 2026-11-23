package org.cargometricsai.bot.config;

import org.cargometricsai.bot.CargoMetricsTelegramBot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.TelegramBotsLongPollingApplication;
import org.telegram.telegrambots.meta.generics.TelegramClient;

@Configuration
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public TelegramClient telegramClient(@Value("${telegram.bot.token}") String botToken) {
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
            @Value("${telegram.bot.token}") String botToken
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
