package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Распознаёт текст сообщения из WhatsApp в позиции заказа через Claude API (tool use,
 * чтобы гарантированно получить структурированный JSON, а не свободный текст). Платный
 * движок — точнее на сленге/опечатках, чем локальный (см. OllamaOrderParserService).
 * Активен, только если {@code crm.order-parser.engine=claude}.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "crm.order-parser", name = "engine", havingValue = "claude")
public class ClaudeOrderParserService implements OrderParser {

    private final RestClient restClient;
    private final String apiKey;
    private final String model;

    public ClaudeOrderParserService(@Value("${crm.claude.api-key:}") String apiKey,
                                     @Value("${crm.claude.model}") String model) {
        this.restClient = RestClient.builder().baseUrl("https://api.anthropic.com").build();
        this.apiKey = apiKey;
        this.model = model;
    }

    @Override
    public ParsedOrder parse(String messageText, List<Product> catalog, Map<Long, List<String>> aliasesByProduct) {
        if (apiKey.isBlank()) {
            throw new IllegalStateException("Не настроен ключ Claude API (crm.claude.api-key)");
        }
        String system = OrderParserPrompt.build(catalog, aliasesByProduct);

        Map<String, Object> tool = Map.of(
                "name", "extract_order",
                "description", "Извлечённые из сообщения клиента позиции заказа",
                "input_schema", Map.of(
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
                )
        );

        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 1024,
                "system", system,
                "messages", List.of(Map.of("role", "user", "content", messageText)),
                "tools", List.of(tool),
                "tool_choice", Map.of("type", "tool", "name", "extract_order")
        );

        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", "2023-06-01")
                    .body(body)
                    .retrieve()
                    .body(JsonNode.class);
        } catch (Exception e) {
            throw new IllegalStateException("Claude API недоступен: " + e.getMessage(), e);
        }

        JsonNode toolUse = null;
        for (JsonNode block : response.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                toolUse = block.path("input");
                break;
            }
        }
        if (toolUse == null) {
            throw new IllegalStateException("Claude не вернул структурированный ответ");
        }

        List<ParsedLine> lines = new ArrayList<>();
        for (JsonNode itemNode : (ArrayNode) toolUse.path("items")) {
            String rawText = itemNode.path("rawProductText").asText("");
            BigDecimal qty = BigDecimal.valueOf(itemNode.path("quantity").asDouble(0));
            String productName = itemNode.hasNonNull("productName") ? itemNode.path("productName").asText() : null;
            Product product = ProductMatcher.match(productName, catalog);
            lines.add(new ParsedLine(rawText, qty, product));
        }
        return new ParsedOrder(lines);
    }
}
