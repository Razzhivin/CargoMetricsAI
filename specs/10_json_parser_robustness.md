# Спецификация Итерации №1: Патч №10 — Повышение отказоустойчивости JSON-парсера (Стек-балансировщик скобок)

## 1. Контекст и Локализация Ошибки
При E2E-инференсе модели `yandexgpt-5-pro` произошел сбой на этапе десериализации ответа:
`Unrecognized token 'Для': was expecting (JSON String, Number, Array, Object...)`

### Причина сбоя:
ИИ сгенерировал вводный или пояснительный текст на русском языке перед или после целевого блока JSON (например, *"Для фиксации груза..."*), содержащий случайные фигурные скобки. Существующий метод `extractJsonBlock` через `indexOf("{")` захватил нерелевантный текстовый фрагмент, что привело к передаче невалидного JSON в Jackson `ObjectMapper`.

---

## 2. Архитектурное Решение
Заменить примитивный поиск индексов на **посимвольный алгоритм подсчета баланса скобок (Stack-based Parsing)**. Алгоритм определяет реальные границы корневого JSON-объекта, изолируя его от любого внешнего текста, markdown-тегов или пояснений ИИ.

---

## 3. Изменения в кодовой базе (`org.cargometricsai.service.CargoAnalysisServiceImpl`)

ИИ-агент в IDEA должен заменить метод `extractJsonBlock` на отказоустойчивую реализацию:

```java
// Найти и заменить метод extractJsonBlock в CargoAnalysisServiceImpl.java
private String extractJsonBlock(String text) {
    if (text == null || text.isBlank()) {
        throw new RuntimeException("Входной текст для извлечения JSON пуст");
    }

    int start = -1;
    int bracketCount = 0;

    for (int i = 0; i < text.length(); i++) {
        char ch = text.charAt(i);
        if (ch == '{') {
            if (start == -1) {
                start = i; // Фиксируем начало корневого JSON
            }
            bracketCount++;
        } else if (ch == '}') {
            if (start != -1) {
                bracketCount--;
                if (bracketCount == 0) {
                    // Корневой JSON-объект полностью закрылся
                    return text.substring(start, i + 1);
                }
            }
        }
    }

    // Резервный вариант на случай, если баланс не закрылся, но скобки есть
    int fallbackStart = text.indexOf("{");
    int fallbackEnd = text.lastIndexOf("}");
    if (fallbackStart != -1 && fallbackEnd != -1 && fallbackEnd > fallbackStart) {
        return text.substring(fallbackStart, fallbackEnd + 1);
    }

    throw new RuntimeException("В ответе нейросети не обнаружен валидный JSON-блок. Исходный текст: " + text);
}
```

---

## 4. План повторной валидации (E2E)
1. Агент в IDEA внедряет стек-парсер скобок в `CargoAnalysisServiceImpl`.
2. Перезапустить приложение (`Virtual Threads` активны).
3. Повторить запрос в Postman. Благодаря балансировщику, текст, начинающийся с токена *«Для...»*, будет отброшен, а Jackson получит чистый JSON.

