package com.confectionery.crm.service;

import com.confectionery.crm.domain.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Готовая накладная для заказа — это файл, который бухгалтер/склад вручную прикладывает к
 * документу "Реализация" прямо в 1С (стандартная функция 1С "Присоединённые файлы", доработок
 * конфигурации не требует). CRM ничего не генерирует сама, только забирает то, что уже
 * приложено. Пока к документу ничего не приложили (или заказ ещё не выгружен в 1С, или 1С
 * недоступна) — {@link #findInvoiceFile} возвращает null, и мобильное приложение показывает
 * свою локальную PDF-накладную как запасной вариант (см. OrderApiController.invoice).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCInvoiceService {

    private final OneCClient oneCClient;

    public InvoiceFile findInvoiceFile(Order order) {
        if (!oneCClient.isConfigured() || order.getOneCDocumentKey() == null) {
            return null;
        }
        try {
            OneCClient.AttachedFile file = oneCClient.fetchAttachedFile(order.getOneCDocumentKey());
            if (file == null) return null;
            return new InvoiceFile(file.data(), file.name(), file.extension());
        } catch (Exception e) {
            log.warn("Не удалось получить накладную из 1С для заказа №{}: {}", order.getId(), e.getMessage());
            return null;
        }
    }

    public record InvoiceFile(byte[] data, String name, String extension) {
    }
}
