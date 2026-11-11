# Спецификация Итерации №1: Патч №8-9 — Мультимодальная интеграция YandexGPT Pro 5 с алгоритмами калибровки веса, загрязнений и хрупких материалов (Стекло)

## 1. Контекст и Бизнес-Логика
В ходе E2E-валидации базовых мультимодальных моделей были выявлены критические логические уязвимости: завышение веса каркасных конструкций из-за игнорирования пустот, а также пропуск коммерческих факторов риска (загрязнение кузова, бой хрупких материалов).

Для обеспечения высокой точности SaaS-сервиса CargoMetricsAI и вывода водителя-ИП на целевой доход, проект переводится на флагманскую мультимодальную модель **YandexGPT Pro 5** с одновременным расширением инструкций `SYSTEM_PROMPT` по распознаванию структуры, чистоты и хрупкости материалов (включая стекло и зеркала).

---

## 2. Архитектурное Решение
1. **Флагманское Ядро:** Использовать модель `yandexgpt-5-pro` через OpenAI-совместимый эндпоинт `/chat/completions`.
2. **Формат строки модели:** Полный системный URI вида `gpt://<folder_id>/yandexgpt-5-pro`.
3. **Защита от боя и загрязнений:** Обязать ИИ идентифицировать стекло/зеркала (с жестким требованием пирамид и вертикальной фиксации) и следы строительной грязи (требование защитной пленки).
4. **Контракт данных:** Полная совместимость с ранее разработанными Java 21 Records (`YandexMessage` с полиморфным `content`).

---

## 3. Изменения в кодовой базе (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

ИИ-агент в IDEA должен полностью перезаписать класс сервиса актуальной структурой констант и логики:

```java
package org.cargometricsai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.cargometricsai.domain.dto.CargoAnalysisResponse;
import org.cargometricsai.domain.dto.Dimensions;
import org.cargometricsai.domain.dto.RequestMetadata;
import org.cargometricsai.domain.dto.yandex.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class CargoAnalysisServiceImpl implements CargoAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(CargoAnalysisServiceImpl.class);

    private static final String SYSTEM_PROMPT = """
            Ты — профессиональный ИИ-логист для транспортных компаний. Твоя задача — проанализировать фото, классифицировать груз, оценить его габариты, РЕАЛИСТИЧНЫЙ ВЕС и эксплуатационные риски.

            Выполняй анализ по шагам:
            1. КЛАССИФИКАЦИЯ И МАТЕРИАЛ: Что на фото? Каков материал груза. ОБЯЗАТЕЛЬНО проверяй наличие ХРУПКИХ МАТЕРИАЛОВ (стекло, зеркала, стеклопакеты, витражи, хрупкий пластик, открытая электроника). Оцени чистоту груза (следы цементных смесей, земли, масел, ржавчины, б/у состояние).
            2. ОЦЕНКА МАСШТАБА: Найди объект-референс (лист А4, поддон, дверь, колесо, плитка на полу, кирпич) и строго по нему прикинь размеры. Не завышай высоту из-за ракурсов съемки снизу вверх.
            3. ГАБАРИТЫ И ВЕС (Важно):
               - Оцени размеры одной единицы груза (Д х Ш х В в метрах).
               - Оцени общий объем всей партии на фото (м³).
               - ОЦЕНКА ВЕСА: Разделяй грузы на МОНОЛИТНЫЕ (сплошные) и ПУСТОТЕЛЫЕ/КАРКАСНЫЕ. 
                 Если перед тобой полая или каркасная конструкция (бетономешалка, пустая бочка, резервуар, собранная мебель, трубы), учитывай, что большая часть её объема — воздух! Бытовая бетономешалка весит всего 45-65 кг, а не 150 кг. Выдавай реальный физический сухой вес в диапазоне (от/до).
            4. ТРАНСПОРТ И МАТРИЦА КУЗОВОВ:
               Подбери конкретный тип автомобиля семейства ГАЗель или грузовиков крупнее:
               - Вес до 1.5т, Объём 1-12 м³, Длина груза до 3м -> "Газель Стандарт (3м, до 1.5т)"
               - Вес до 1.5т, Объём 13-18 м³, Длина груза 3.1-4.2м -> "Газель Удлинённая (4.2м, до 1.5т)"
               - Вес до 1.5т, Объём 19-22 м³, Длина груза 4.3-5.0м -> "Газель Увеличенная (5м, до 1.5т)"
               - Если груз неделимый, высокий (>1.8м) или имеет торчащие жесткие элементы — ИСКЛЮЧАЙ малые фургоны (Ларгус/каблук) и рекомендуй Газели с высотой тента/будки от 1.8м или открытый борт.
               - Если общий вес от 1.5т до 5т -> Рекомендуй "Среднетоннажный грузовик (ГАЗон Некст до 5т)".
            5. ЛОГИСТИЧЕСКИЕ ПРИМЕЧАНИЯ (Критически важно):
               - Если в шаге 1 обнаружено СТЕКЛО, ЗЕРКАЛА или стеклопакеты — ОБЯЗАТЕЛЬНО требуй: "ГРУЗ КРИТИЧЕСКИ ХРУПКИЙ! Запрещена укладка плашмя/навалом. Требуется транспортировка строго вертикально, использование специализированных пирамид, обрешетки, ремней фиксации и пенопластовых/пузырьковых прокладок".
               - Выяви хрупкие навесные элементы (электромоторы, пластиковые кожухи, ручки).
               - Оцени риски опрокидывания (наличие колес в основании, смещенный центр тяжести).
               - Если груз грязный — ОБЯЗАТЕЛЬНО выдай предупреждение: "Груз загрязнен. Для сохранения обшивки и настила кузова ТС требуется использование защитной пленки, картона или покрывал".

            Выдай ответ строго в формате JSON без markdown-разметки (без ```json):
            {
              "cargo_type": "тип груза, материал (явно указать, если есть стекло/хрупкое), состояние б/у или новое",
              "packaging": "тип упаковки и отметка о загрязнении груза при наличии",
              "reference_object_found": "маркер масштаба",
              "single_unit_dimensions_m": {"length": 0.00, "width": 0.00, "height": 0.00},
              "total_estimated_volume_m3": 0.00,
              "estimated_weight_range_kg": "от 00 до 00",
              "recommended_vehicle": "конкретный тип авто из матрицы кузовов",
              "logistic_notes": "детальный комментарий: требования к пирамидам для стекол, хрупкие навесные узлы, риски опрокидывания, защита от грязи пленкой"
            }
            """;

    @Value("\${yandex.api.folder-id}")
    private String folderId;

    private final RestClient yandexRestClient;
    private final ObjectMapper objectMapper;

    public CargoAnalysisServiceImpl(
            @Qualifier("yandexRestClient") RestClient yandexRestClient,
            ObjectMapper objectMapper) {
        this.yandexRestClient = yandexRestClient;
        this.objectMapper = objectMapper;
    }

    @Override
    public CargoAnalysisResponse analyzeCargoImages(List<MultipartFile> images, RequestMetadata metadata) {
        validateImages(images);

        // Инференс через флагманское мультимодальное ядро YandexGPT Pro 5
        String dynamicModelUri = "gpt://" + folderId + "/yandexgpt-5-pro"; 

        List<YandexMessage> messages = new ArrayList<>();
        messages.add(new YandexMessage("system", SYSTEM_PROMPT));

        List<Object> userContents = new ArrayList<>();
        // Стратегия Prompt-First для OpenAI-совместимого прокси Яндекса
        userContents.add(new TextContent("text", "Проанализируй этот груз по фотографии, определи габариты, вес, загрязнения и риски согласно системным инструкциям."));

        for (MultipartFile image : images) {
            try {
                String base64Image = java.util.Base64.getEncoder().encodeToString(image.getBytes());
                String contentType = image.getContentType() != null ? image.getContentType() : "image/jpeg";
                String dataUrl = "data:" + contentType + ";base64," + base64Image;

                userContents.add(new ImageUrlContent("image_url", new ImageUrlInfo(dataUrl)));
            } catch (Exception e) {
                log.error("Критическая ошибка кодирования изображения для арендатора: {}", metadata.tenantId(), e);
                throw new RuntimeException("Ошибка конвертации изображения", e);
            }
        }
        
        messages.add(new YandexMessage("user", userContents));
        YandexChatRequest request = new YandexChatRequest(dynamicModelUri, messages, 0.3);

        log.info("Отправка мультимодального запроса в YandexGPT Pro 5 для арендатора: {}", metadata.tenantId());

        Map<String, Object> response = yandexRestClient.post()
                .uri("/chat/completions")
                .body(request)
                .retrieve()
                .body(Map.class);

        return parseResponse(response);
    }

    private void validateImages(List<MultipartFile> images) {
        if (images == null || images.isEmpty() || images.size() > 3) {
            throw new IllegalArgumentException("Количество фотографий груза должно быть от 1 до 3");
        }
    }

    private CargoAnalysisResponse parseResponse(Map<String, Object> response) {
        try {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.get("choices");
            if (choices == null || choices.isEmpty()) {
                throw new RuntimeException("Отсутствует массив choices в ответе Яндекса");
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
            String fullText = (String) message.get("content");

            if (fullText == null || fullText.isBlank()) {
                throw new RuntimeException("Отсутствует контент ответа нейросети");
            }

            String jsonStr = extractJsonBlock(fullText);
            Map<String, Object> jsonMap = objectMapper.readValue(jsonStr, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            Map<String, Object> dimsMap = (Map<String, Object>) jsonMap.get("single_unit_dimensions_m");
