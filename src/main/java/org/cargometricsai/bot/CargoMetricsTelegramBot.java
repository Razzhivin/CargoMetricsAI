package org.cargometricsai.bot;

import org.cargometricsai.bot.service.TrialRateLimiterService;
import org.cargometricsai.bot.service.TelegramNotificationFormatter;
import org.cargometricsai.bot.util.InMemoryMultipartFile;
import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.cargometricsai.service.CargoAnalysisService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.TelegramBotsApi;
import org.telegram.telegrambots.meta.generics.BotSession;

import jakarta.annotation.PostConstruct;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "telegram.bot.token")
public class CargoMetricsTelegramBot extends TelegramLongPollingBot {

    private static final Logger log = LoggerFactory.getLogger(CargoMetricsTelegramBot.class);

    private final CargoAnalysisService cargoAnalysisService;
    private final TrialRateLimiterService rateLimiter;
    private final TelegramNotificationFormatter formatter;
    private final long adminChatId;
    private final String botToken;
    private final String botUsername;

    private final Map<String, CargoAnalysisResponse> lastResponses = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    public CargoMetricsTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            CargoAnalysisService cargoAnalysisService,
            TrialRateLimiterService rateLimiter,
            TelegramNotificationFormatter formatter,
            @Value("${telegram.bot.admin-chat-id}") long adminChatId) {
        super(new org.telegram.telegrambots.bots.DefaultBotOptions());
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.cargoAnalysisService = cargoAnalysisService;
        this.rateLimiter = rateLimiter;
        this.formatter = formatter;
        this.adminChatId = adminChatId;
    }

    @PostConstruct
    public void registerBot() {
        try {
            TelegramBotsApi botsApi = new TelegramBotsApi(BotSession.class);
            botsApi.registerBot(this);
            log.info("Telegram bot registered: {}", botUsername);
        } catch (TelegramApiException e) {
            log.error("Failed to register Telegram bot: {}", e.getMessage());
        }
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            Message message = update.getMessage();
            if (message.hasText()) {
                handleTextMessage(message);
            } else if (message.getPhoto() != null && !message.getPhoto().isEmpty()) {
                handlePhotoMessage(message.getChatId(), message.getPhoto());
            }
        }
    }

    private void handleTextMessage(Message message) {
        Long chatId = message.getChatId();
        String text = message.getText().trim();

        if ("/start".equals(text)) {
            sendStartMessage(chatId);
            return;
        }

        if ("/help".equals(text)) {
            sendHelpMessage(chatId);
            return;
        }
    }

    private void handlePhotoMessage(Long chatId, List<PhotoSize> photos) {
        if (photos == null || photos.isEmpty()) return;
        processImages(chatId, photos);
    }

    private void processImages(Long chatId, List<PhotoSize> photos) {
        if (!rateLimiter.canProcess(chatId)) {
            sendMessage(chatId, formatter.formatTrialLimitMessage());
            return;
        }

        try {
            List<org.springframework.web.multipart.MultipartFile> files = new ArrayList<>();
            for (PhotoSize photo : photos) {
                String fileId = photo.getFileId();
                byte[] imageBytes = downloadImage(fileId);
                files.add(new InMemoryMultipartFile(
                        imageBytes,
                        "photo_" + photo.getFileUniqueId(),
                        "photo.jpg",
                        "image/jpeg"
                ));
            }

            RequestMetadata metadata = new RequestMetadata(
                    "TELEGRAM",
                    "TELEGRAM",
                    String.valueOf(chatId)
            );

            int usedCount = rateLimiter.incrementAndGet(chatId);
            int remaining = rateLimiter.getRemaining(chatId);

            CargoAnalysisResponse response = cargoAnalysisService.analyzeCargoImages(files, metadata);
            lastResponses.put(response.requestId().toString(), response);

            String message;
            if (remaining == 0) {
                message = formatter.formatLogisticCard(response, remaining) + "\n\n" + formatter.formatTrialLimitMessage();
            } else {
                message = formatter.formatLogisticCard(response, remaining);
            }

            sendMessageWithKeyboard(chatId, message);
            notifyAdmin(chatId, response);

        } catch (Exception e) {
            log.error("Ошибка анализа для chatId {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, "❌ Ошибка анализа: " + e.getMessage());
        }
    }

    private void sendStartMessage(Long chatId) {
        String message = "👋 <b>Добро пожаловать в CargoMetricsAI!</b>%n%n" +
                "Я — ИИ-логист, который анализирует фотографии груза:%n" +
                "📐 Габариты (Д×Ш×В)%n" +
                "📊 Объём (м³)%n" +
                "⚖️ Вес (кг)%n" +
                "🚛 Рекомендуемый транспорт%n%n" +
                "📸 Отправьте 1–3 фото груза%n" +
                "🎁 Вам доступно <b>3 бесплатных анализа</b>.%n%n" +
                "Пришлите фото груза, чтобы начать!";
        sendMessage(chatId, message);
    }

    private void sendHelpMessage(Long chatId) {
        String message = "📖 <b>Помощь</b>%n%n" +
                "📸 Отправьте 1–3 фото груза%n" +
                "🎁 Осталось бесплатных тестов: " + rateLimiter.getRemaining(chatId);
        sendMessage(chatId, message);
    }

    private void sendMessageWithKeyboard(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();

        var row = new KeyboardRow();
        KeyboardButton button = KeyboardButton.builder()
                .text("🚀 Подключить компанию")
                .requestContact(false)
                .build();
        row.add(button);

        var keyboard = new ReplyKeyboardMarkup(List.of(row));
        message.setReplyMarkup(keyboard);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }

    private void sendMessage(Long chatId, String text) {
        SendMessage message = SendMessage.builder()
                .chatId(chatId.toString())
                .text(text)
                .parseMode("HTML")
                .build();
        try {
            execute(message);
        } catch (TelegramApiException e) {
            log.error("Failed to send message: {}", e.getMessage());
        }
    }

    private void notifyAdmin(Long chatId, CargoAnalysisResponse response) {
        try {
            String message = formatter.formatAdminNotification(null, chatId, response.cargoType(), response.recommendedVehicle());
            SendMessage adminMessage = SendMessage.builder()
                    .chatId(String.valueOf(adminChatId))
                    .text(message)
                    .parseMode("HTML")
                    .build();
            execute(adminMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to notify admin: {}", e.getMessage());
        }
    }

    private byte[] downloadImage(String fileId) throws Exception {
        String url = "https://api.telegram.org/file/bot" + botToken + "/" + fileId;
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setRequestMethod("GET");
        try (InputStream in = conn.getInputStream()) {
            return in.readAllBytes();
        }
    }
}
