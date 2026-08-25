package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Распознаёт текст сообщения из WhatsApp в позиции заказа. Два движка на выбор (см.
 * {@code crm.order-parser.engine}): {@link ClaudeOrderParserService} (платный, точнее на
 * сленге/опечатках) и {@link OllamaOrderParserService} (бесплатный, работает локально).
 * Оба используют один и тот же каталог + синонимы (см. ProductService) и один и тот же
 * контракт результата, поэтому WhatsAppOrderIntakeService не знает, какой движок активен.
 */
public interface OrderParser {

    record ParsedLine(String rawProductText, BigDecimal quantity, Product product) {
    }

    record ParsedOrder(List<ParsedLine> lines) {
        public boolean allMatched() {
            return !lines.isEmpty() && lines.stream().allMatch(l -> l.product() != null);
        }
    }

    /** @throws IllegalStateException если движок не настроен или запрос не удался. */
    ParsedOrder parse(String messageText, List<Product> catalog, Map<Long, List<String>> aliasesByProduct);
}
