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
import org.telegram.telegrambots.client.okhttp.OkHttpTelegramClient;
import org.telegram.telegrambots.longpolling.util.LongPollingSingleThreadUpdateConsumer;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;

import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.message.Message;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;

import java.io.InputStream;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
@ConditionalOnProperty(name = "telegram.bot.token")
public class CargoMetricsTelegramBot implements LongPollingSingleThreadUpdateConsumer {

    private static final Logger log = LoggerFactory.getLogger(CargoMetricsTelegramBot.class);
    private static final String SAFE_ERROR_MSG = "⚠️ Не удалось обработать фотографии. Пожалуйста, убедитесь, что отправляете четкие изображения (до 3 штук), и попробуйте еще раз.";

    private final CargoAnalysisService cargoAnalysisService;
    private final TrialRateLimiterService rateLimiter;
    private final TelegramNotificationFormatter formatter;
    private final long adminChatId;
    private final String botToken;
    private final String botUsername;
    private final OkHttpTelegramClient telegramClient;

    private final Map<String, CargoAnalysisResponse> lastResponses = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<String>> mediaGroupBuffer = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    public CargoMetricsTelegramBot(
            @Value("${telegram.bot.token}") String botToken,
            @Value("${telegram.bot.username}") String botUsername,
            CargoAnalysisService cargoAnalysisService,
            TrialRateLimiterService rateLimiter,
            TelegramNotificationFormatter formatter,
            @Value("${telegram.bot.admin-chat-id}") long adminChatId) {
        this.botToken = botToken;
        this.botUsername = botUsername;
        this.cargoAnalysisService = cargoAnalysisService;
        this.rateLimiter = rateLimiter;
        this.formatter = formatter;
        this.adminChatId = adminChatId;
        this.telegramClient = new OkHttpTelegramClient(botToken);
    }

    public String getBotToken() {
        return botToken;
    }

    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public void consume(Update update) {
        log.info("Входящий Update из Telegram: updateId={}", update.getUpdateId());

        if (update.hasMessage()) {
            Message message = update.getMessage();
            if (message.hasText()) {
                handleTextMessage(message);
            } else if (message.getMediaGroupId() != null) {
                handleMediaGroupAlbum(message.getChatId(), message.getMediaGroupId(), message);
            } else {
                Optional<String> bestFileId = extractBestPhotoFileId(message);
                if (bestFileId.isPresent()) {
                    processSingleImage(message.getChatId(), bestFileId.get());
                } else {
                    sendMessage(message.getChatId(), "Пожалуйста, отправьте или перешлите 1–3 фотографии груза (сжатым фото или файлом), чтобы я рассчитал габариты и риски.");
                }
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

    private void processSingleImage(Long chatId, String fileId) {
        if (!rateLimiter.canProcess(chatId)) {
            sendMessage(chatId, formatter.formatTrialLimitMessage());
            return;
        }

        try {
            InMemoryMultipartFile file = downloadImage(fileId, 0);

            RequestMetadata metadata = new RequestMetadata(
                    "TELEGRAM",
                    "TELEGRAM",
                    String.valueOf(chatId)
            );

            int usedCount = rateLimiter.incrementAndGet(chatId);
            int remaining = rateLimiter.getRemaining(chatId);

            CargoAnalysisResponse response = cargoAnalysisService.analyzeCargoImages(List.of(file), metadata);
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
            sendMessage(chatId, SAFE_ERROR_MSG);
        }
    }

    private void handleMediaGroupAlbum(Long chatId, String mediaGroupId, Message message) {
        Optional<String> bestFileId = extractBestPhotoFileId(message);
        if (bestFileId.isEmpty()) return;

        String fileId = bestFileId.get();
        AtomicBoolean isFirstFrame = new AtomicBoolean(false);

        mediaGroupBuffer.compute(mediaGroupId, (key, existingList) -> {
            if (existingList == null) {
                isFirstFrame.set(true);
                List<String> newList = Collections.synchronizedList(new ArrayList<>());
                newList.add(fileId);
                return newList;
            }
            existingList.add(fileId);
            return existingList;
        });

        if (isFirstFrame.get()) {
            scheduler.schedule(() -> {
                List<String> collected = mediaGroupBuffer.remove(mediaGroupId);
                if (collected != null && !collected.isEmpty()) {
                    log.info("Альбом mediaGroupId={} собран: всего файлов для скачивания = {}", mediaGroupId, collected.size());
                    List<String> limited = collected.stream().limit(3).toList();
                    processFileIds(chatId, limited);
                }
            }, 2500, TimeUnit.MILLISECONDS);
        }
    }

    private void processFileIds(Long chatId, List<String> fileIds) {
        if (!rateLimiter.canProcess(chatId)) {
            sendMessage(chatId, formatter.formatTrialLimitMessage());
            return;
        }

        try {
            List<org.springframework.web.multipart.MultipartFile> files = new ArrayList<>();
            for (int i = 0; i < fileIds.size(); i++) {
                log.info("Скачивание файла {}/{} из Telegram", i + 1, fileIds.size());
                InMemoryMultipartFile file = downloadImage(fileIds.get(i), i);
                files.add(file);
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
            log.error("Ошибка анализа медиаальбома для chatId {}: {}", chatId, e.getMessage(), e);
            sendMessage(chatId, SAFE_ERROR_MSG);
        }
    }

    private void sendStartMessage(Long chatId) {
        String message = "👋 <b>Добро пожаловать в CargoMetricsAI!</b>\n\n" +
                "Я — ИИ-логист, который анализирует фотографии груза:\n" +
                "📐 Габариты (Д×Ш×В)\n" +
                "📊 Объём (м³)\n" +
                "⚖️ Вес (кг)\n" +
                "🚛 Рекомендуемый транспорт\n\n" +
                "📸 Отправьте 1–3 фото груза\n" +
                "🎁 Вам доступно <b>3 бесплатных анализа</b>.\n\n" +
                "Пришлите фото груза, чтобы начать!";
        sendMessage(chatId, message);
    }

    private void sendHelpMessage(Long chatId) {
        String message = "📖 <b>Помощь</b>\n\n" +
                "📸 Отправьте 1–3 фото груза\n" +
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
            telegramClient.execute(message);
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
            telegramClient.execute(message);
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
            telegramClient.execute(adminMessage);
        } catch (TelegramApiException e) {
            log.error("Failed to notify admin: {}", e.getMessage());
        }
    }

    private InMemoryMultipartFile downloadImage(String fileId, int index) throws Exception {
        GetFile getFileRequest = GetFile.builder()
                .fileId(fileId)
                .build();
        org.telegram.telegrambots.meta.api.objects.File telegramFile = telegramClient.execute(getFileRequest);
        String filePath = telegramFile.getFilePath();
        String downloadUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

        log.info("Скачивание файла из Telegram: path={}", filePath);

        try (InputStream is = URI.create(downloadUrl).toURL().openStream()) {
            byte[] bytes = is.readAllBytes();
            log.info("Скачан файл из Telegram: path={}, size={} bytes", filePath, bytes.length);

            if (bytes == null || bytes.length < 100) {
                throw new IllegalStateException(
                        "Получен пустой или поврежденный файл из Telegram (size=" + (bytes == null ? 0 : bytes.length) + ")"
                );
            }

            String mimeType = detectMimeType(bytes);

            return new InMemoryMultipartFile(
                    bytes,
                    "cargo_" + index + ".jpg",
                    "image.jpg",
                    mimeType
            );
        }
    }

    private String detectMimeType(byte[] bytes) {
        if (bytes.length < 2) {
            throw new IllegalStateException("Недостаточно байт для определения MIME-типа");
        }

        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8) {
            return "image/jpeg";
        }

        if ((bytes[0] & 0xFF) == 0x89 && (bytes[1] & 0xFF) == 0x50) {
            return "image/png";
        }

        if (bytes.length >= 12 &&
                (bytes[0] & 0xFF) == 0x52 &&
                (bytes[1] & 0xFF) == 0x49 &&
                (bytes[2] & 0xFF) == 0x46 &&
                (bytes[3] & 0xFF) == 0x46 &&
                (bytes[8] & 0xFF) == 0x57 &&
                (bytes[9] & 0xFF) == 0x45 &&
                (bytes[10] & 0xFF) == 0x42 &&
                (bytes[11] & 0xFF) == 0x50) {
            return "image/webp";
        }

        log.warn("Неизвестная сигнатура файла, первые 100 байт: {}", extractHexSignature(bytes));
        throw new IllegalStateException("Неизвестный формат изображения из Telegram");
    }

    private String extractHexSignature(byte[] bytes) {
        int limit = Math.min(bytes.length, 100);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            if (i > 0) sb.append(" ");
            sb.append(String.format("%02X", bytes[i] & 0xFF));
        }
        return sb.toString();
    }
}
