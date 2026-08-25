package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Распознаёт текст сообщения из WhatsApp в позиции заказа через локальную модель (Ollama,
 * см. crm.ollama.*) — бесплатный движок, без обращения к внешнему API. Точность на сленге/
 * опечатках ниже, чем у Claude, компенсируется справочником синонимов (ProductAlias) —
 * см. WhatsAppOrderIntakeService.resolvePending. Активен по умолчанию (или явно
 * {@code crm.order-parser.engine=ollama}).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "crm.order-parser", name = "engine", havingValue = "ollama", matchIfMissing = true)
public class OllamaOrderParserService implements OrderParser {

    /** JSON-схема ответа — Ollama (llama.cpp) генерирует по ней валидный JSON принудительно (grammar). */
    private static final Map<String, Object> RESPONSE_SCHEMA = Map.of(
            "type", "object",
            "properties", Map.of(
                    "items", Map.of(
                            "type", "array",
                            "items", Map.of(
                                    "type", "object",
                                    "properties", Map.of(
                                            "rawProductText", Map.of("type", "string"),
                                            "quantity", Map.of("type", "number"),
                                            "productName", Map.of("type", List.of("string", "null"))
                                    ),
                                    "required", List.of("rawProductText", "quantity", "productName")
                            )
                    )
            ),
            "required", List.of("items")
    );

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final String model;

    public OllamaOrderParserService(@Value("${crm.ollama.base-url}") String baseUrl,
                                     @Value("${crm.ollama.model}") String model,
                                     ObjectMapper objectMapper) {
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.model = model;
    }

    @Override
    public ParsedOrder parse(String messageText, List<Product> catalog, Map<Long, List<String>> aliasesByProduct) {
        String system = OrderParserPrompt.build(catalog, aliasesByProduct);

        Map<String, Object> body = Map.of(
                "model", model,
                "stream", false,
                "format", RESPONSE_SCHEMA,
                "messages", List.of(
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user", "content", messageText)
                )
        );

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/api/chat")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Локальная модель (Ollama) недоступна: " + e.getMessage(), e);
        }

        String content = response.path("message").path("content").asText("");
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(content);
        } catch (Exception e) {
            throw new IllegalStateException("Локальная модель вернула невалидный JSON: " + e.getMessage(), e);
        }

        List<ParsedLine> lines = new ArrayList<>();
        for (JsonNode itemNode : (ArrayNode) parsed.path("items")) {
            String rawText = itemNode.path("rawProductText").asText("");
            BigDecimal qty = BigDecimal.valueOf(itemNode.path("quantity").asDouble(0));
            String productName = itemNode.hasNonNull("productName") ? itemNode.path("productName").asText() : null;
            Product product = ProductMatcher.match(productName, catalog);
            lines.add(new ParsedLine(rawText, qty, product));
        }
        return new ParsedOrder(lines);
    }
}
