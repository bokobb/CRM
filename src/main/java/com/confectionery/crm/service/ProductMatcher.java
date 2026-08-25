package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Сопоставляет productName, который вернула модель (Claude или Ollama), с реальным
 * товаром каталога. Небольшие модели (и иногда Claude) не всегда копируют название
 * дословно — типичный случай: у товара "Муравейник (18 шт)" модель возвращает просто
 * "Муравейник", посчитав указание фасовки в скобках не частью названия. Сначала пробуем
 * точное совпадение, если не вышло — совпадение без скобочного суффикса (и только если
 * оно однозначное — иначе лучше не угадывать).
 */
final class ProductMatcher {

    private static final Pattern TRAILING_PARENTHETICAL = Pattern.compile("\\s*\\([^)]*\\)\\s*$");

    private ProductMatcher() {
    }

    static Product match(String productName, List<Product> catalog) {
        if (productName == null || productName.isBlank()) return null;
        String needle = productName.trim().toLowerCase();

        for (Product p : catalog) {
            if (p.getName().trim().toLowerCase().equals(needle)) return p;
        }

        Product onlyMatch = null;
        for (Product p : catalog) {
            if (stripParenthetical(p.getName()).equals(needle)) {
                if (onlyMatch != null) return null; // неоднозначно — несколько товаров сократились до одного текста
                onlyMatch = p;
            }
        }
        return onlyMatch;
    }

    private static String stripParenthetical(String name) {
        return TRAILING_PARENTHETICAL.matcher(name.trim().toLowerCase()).replaceAll("").trim();
    }
}
