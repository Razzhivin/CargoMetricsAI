Instruction for AI Agent:
Fix the compilation error by creating the missing Java 21 records required for OpenAI multimodal serialization.

Steps:
1. Create a new file named `ImageUrlContent.java` inside the package `org.cargometricsai.domain.dto.yandex`.
2. Implement it using the exact Java 21 syntax outlined below:

package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageUrlContent(
@JsonProperty("type") String type, // Всегда передается "image_url"
@JsonProperty("image_url") ImageUrlInfo imageUrl
) {}

3. В этом же файле (или в отдельном файле `ImageUrlInfo.java` в том же пакете) создай вспомогательный рекорд `ImageUrlInfo`:

package org.cargometricsai.domain.dto.yandex;

import com.fasterxml.jackson.annotation.JsonProperty;

public record ImageUrlInfo(
@JsonProperty("url") String url // Сюда будет уходить Data URL схема (data:image/jpeg;base64,...)
) {}

4. Убедись, что в файле `CargoAnalysisServiceImpl.java` импорты настроены корректно (`import org.cargometricsai.domain.dto.yandex.*;`) и класс видит новые рекорды.

Перекомпилируй проект и запусти приложение.
