Instruction for AI Agent:
Modify the properties of `ImageContent` record to ensure the type field sends "image" while keeping the property payload key as "image_bytes".

Steps:
1. Open `org.cargometricsai.domain.dto.yandex.ImageContent`.
2. Refactor it to match this scheme:

   package org.cargometricsai.domain.dto.yandex;
   import com.fasterxml.jackson.annotation.JsonProperty;

   public record ImageContent(
   @JsonProperty("type") String type,       // This MUST be set to "image" when instantiated
   @JsonProperty("image_bytes") String imageBytes // This holds the raw base64 string
   ) {}

3. Open `org.cargometricsai.service.CargoAnalysisServiceImpl`.
4. Locate the `buildUserMessages` method. Modify the instantiation of `ImageContent`:
   Change from:
   contents.add(new ImageContent("image_bytes", base64Image));
   To:
   contents.add(new ImageContent("image", base64Image)); // "type" parameter must be "image"!

5. Verify that `TextContent` uses "type": "text".
6. Re-run and build the project, then verify the response via Postman.
