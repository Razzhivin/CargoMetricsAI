package org.cargometricsai.bot.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.generics.BotSession;

@Configuration
public class TelegramBotConfig {

    private static final Logger log = LoggerFactory.getLogger(TelegramBotConfig.class);

    @Bean
    public ApplicationRunner botRegistrar(
            TelegramLongPollingBot bot
    ) {
        return args -> {
            try {
                TelegramBotsApi botsApi = new TelegramBotsApi(BotSession.class);
                botsApi.registerBot(bot);
                log.info("=== TELEGRAM БОТ УСПЕШНО ЗАРЕГИСТРИРОВАН И СЛУШАЕТ ОБНОВЛЕНИЯ ===");
            } catch (TelegramApiException e) {
                if (e.getMessage() != null && e.getMessage().contains("already registered")) {
                    log.info("=== TELEGRAM БОТ УЖЕ ЗАРЕГИСТРИРОВАН АВТОКОНФИГУРАЦИЕЙ ===");
                } else {
                    log.error("=== ОШИБКА РЕГИСТРАЦИИ TELEGRAM БОТА ===", e);
                }
            }
        };
    }
}
