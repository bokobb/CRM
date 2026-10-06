package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Коды номенклатуры 1С → Product.oneCCode: бланк «Объём и масса» приложение собирает само, без
 * 1С, а колонка «Код» должна совпадать с накладной. Сверка раз в сутки и вскоре после старта;
 * код в 1С поменяли — к утру поправится и в CRM.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCProductCodeSyncService {

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;

    @Scheduled(cron = "${crm.onec.product-code-sync-cron:0 30 5 * * *}", zone = "${crm.schedule-zone:GMT+05:00}")
    public void scheduled() {
        sync();
    }

    @Scheduled(initialDelay = 120_000, fixedDelay = Long.MAX_VALUE)
    public void onStartup() {
        sync();
    }

    @Transactional
    public void sync() {
        if (!oneCClient.isConfigured()) return;
        try {
            Map<String, String> codes = oneCClient.fetchNomenclatureCodes();
            List<Product> changed = new ArrayList<>();
            for (Product p : productRepository.findAll()) {
                if (p.getOneCNomenclatureKey() == null) continue;
                String code = codes.get(p.getOneCNomenclatureKey());
                if (code != null && !code.isBlank() && !Objects.equals(code, p.getOneCCode())) {
                    p.setOneCCode(code);
                    changed.add(p);
                }
            }
            productRepository.saveAll(changed);
            if (!changed.isEmpty()) {
                log.info("Коды 1С обновлены у {} товаров", changed.size());
            }
        } catch (Exception e) {
            log.warn("Не удалось сверить коды товаров с 1С: {}", e.getMessage());
        }
    }
}
