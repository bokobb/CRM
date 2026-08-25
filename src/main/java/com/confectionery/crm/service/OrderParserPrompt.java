package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;

import java.util.List;
import java.util.Map;

/** Общий системный промпт для обоих движков распознавания заказа (Claude и Ollama). */
final class OrderParserPrompt {

    private OrderParserPrompt() {
    }

    static String build(List<Product> catalog, Map<Long, List<String>> aliasesByProduct) {
        StringBuilder catalogList = new StringBuilder();
        for (Product p : catalog) {
            List<String> aliases = aliasesByProduct.getOrDefault(p.getId(), List.of());
            catalogList.append("- ").append(p.getName());
            if (!aliases.isEmpty()) {
                catalogList.append(" (ещё называют: ").append(String.join(", ", aliases)).append(")");
            }
            catalogList.append('\n');
        }
        return """
                Ты обрабатываешь сообщения клиентов кондитерской из WhatsApp и превращаешь их в заказ.
                Клиенты часто пишут коротко, с опечатками и разговорными названиями.
                Вот точный список товаров в каталоге, у некоторых указаны разговорные синонимы,
                которыми их называют клиенты (в скобках "ещё называют"):
                %s

                Разбери сообщение клиента на позиции заказа. Для КАЖДОЙ строки сообщения, похожей
                на позицию заказа (название + количество), верни отдельный элемент со свойствами:
                - rawProductText — только название товара, как написал клиент, БЕЗ количества
                  (например "барни", "розочка"), в исходном виде, без изменений и додумывания.
                - quantity — количество (число).
                - productName — точное название товара из каталога выше, если ты уверен в
                  соответствии (по смыслу, синонимам, опечаткам); если нет уверенности — null.
                  Не выдумывай названия, которых нет в каталоге, и не угадывай наугад, если
                  формулировка клиента подходит сразу к нескольким товарам одинаково хорошо.
                """.formatted(catalogList);
    }
}
