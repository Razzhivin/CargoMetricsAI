package org.cargometricsai.bot.service;

import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.Dimensions;
import org.springframework.stereotype.Service;

@Service
public class TelegramNotificationFormatter {

    public String formatLogisticCard(CargoAnalysisResponse response, int remainingTrials) {
        Dimensions dims = response.singleUnitDimensionsM();

        String notes = formatLogisticNotes(response.logisticNotes());
        String trialsText = formatTrialsText(remainingTrials);

        return String.format(
                "📦 <b>Анализ груза</b>\n\n" +
                        "🔹 <b>Тип:</b> %s\n" +
                        "📐 <b>Габариты (Д×Ш×В):</b> %.2f × %.2f × %.2f м\n" +
                        "📊 <b>Объём:</b> %.2f м³\n" +
                        "⚖️ <b>Вес:</b> %s кг\n" +
                        "🚛 <b>Транспорт:</b> %s\n\n" +
                        "📝 <i>%s</i>\n\n" +
                        "%s",
                escapeHtml(response.cargoType()),
                dims.length(),
                dims.width(),
                dims.height(),
                response.totalEstimatedVolumeM3(),
                response.estimatedWeightRangeKg(),
                response.recommendedVehicle(),
                escapeHtml(notes),
                trialsText
        );
    }

    public String formatAtiSuTemplate(CargoAnalysisResponse response) {
        Dimensions dims = response.singleUnitDimensionsM();

        String notes = formatLogisticNotes(response.logisticNotes());

        return String.format(
                "Груз: %s\n" +
                        "Габариты (Д×Ш×В): %.2f × %.2f × %.2f м\n" +
                        "Объём: %.2f м³\n" +
                        "Вес: %s кг\n" +
                        "Транспорт: %s\n" +
                        "Примечания: %s",
                escapeHtml(response.cargoType()),
                dims.length(),
                dims.width(),
                dims.height(),
                response.totalEstimatedVolumeM3(),
                response.estimatedWeightRangeKg(),
                response.recommendedVehicle(),
                escapeHtml(notes)
        );
    }

    public String formatTrialLimitMessage() {
        return "⛔ <b>Бесплатные тесты исчерпаны</b>\n\n" +
                "Вы использовали все 3 бесплатных анализа.\n" +
                "Для подключения компании и безлимитного доступа:\n" +
                "📞 Свяжитесь с отделом логистики:\n" +
                "✈️ @cargometrics_sales\n\n" +
                "Мы предложим оптимальный тариф для вашей ТК.";
    }

    public String formatAdminNotification(String username, Long userId, String cargoType, String vehicle) {
        return String.format(
                "🔔 <b>Новый тест в боте!</b>\n\n" +
                        "👤 Пользователь: %s (ID: %d)\n" +
                        "📦 Груз: %s\n" +
                        "🚛 Транспорт: %s",
                username != null ? "@" + username : "Unknown",
                userId,
                escapeHtml(cargoType),
                escapeHtml(vehicle)
        );
    }

    private String escapeHtml(String text) {
        if (text == null) return "";
        return text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }

    private String formatLogisticNotes(String notes) {
        if (notes == null || notes.isBlank()) return "";
        return notes.replaceAll("\\s+(\\d+\\))", "\n$1");
    }

    private String formatTrialsText(int remainingTrials) {
        if (remainingTrials == Integer.MAX_VALUE) {
            return "👑 Режим администратора (безлимитный доступ)";
        }
        return "🎯 Осталось бесплатных тестов: " + remainingTrials;
    }
}
