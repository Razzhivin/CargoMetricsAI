# Спецификация Итерации №2: Патч №21 — Устойчивая регистрация Telegram-бота (Retry при HTTP 429)

## 1. Контекст и Проблема
При запуске `BotSession.start()` библиотека `telegrambots-longpolling` вызывает `deleteWebhook`, который может вернуть `429 Too Many Requests` при частых перезапусках приложения или использовании общих VPN-шлюзов. В текущей реализации ошибка перехватывается один раз и бот не запускает цикл получения обновлений (`getUpdates`).

## 2. Изменения в `org.cargometricsai.bot.config.TelegramBotConfig`
Обновить бин `botRegistrar`, запустив регистрацию в виртуальном потоке (`Thread.startVirtualThread`) с циклом повторных попыток (до 5 попыток с паузой 15 секунд при получении ошибки `429`):

```java
    @Bean
    public ApplicationRunner botRegistrar(
            TelegramBotsLongPollingApplication botsApplication,
            CargoMetricsTelegramBot bot,
            @Value("\${telegram.bot.token}") String botToken
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
```

## 3. Критерии приемки (DoD)
1. Регистрация бота не блокирует основной поток запуска Spring Boot.
2. При временной ошибке `429` приложение автоматически повторяет попытку каждые 15 секунд и успешно запускает поллинг после снятия лимита Telegram.
