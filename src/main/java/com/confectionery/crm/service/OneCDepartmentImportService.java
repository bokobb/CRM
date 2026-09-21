package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Разовый (по кнопке) импорт "настоящего" подразделения (Производство Корпус А/B/С/D) для
 * каждого товара — определяется по истории РЕАЛЬНЫХ документов "Отчёт производства за смену" в
 * 1С, заведённых вручную (не через CRM): у каждого товара подразделение почти всегда одно и то
 * же во всех таких документах (см. {@link #importAll}, голосование большинством на случай
 * единичных опечаток), поэтому берём самое частое. Документы, созданные самой CRM, в расчёт не
 * идут — они все получают одно и то же подразделение из
 * {@code crm.onec.production-department-key} (запасной вариант, см. OneCProductionSyncService),
 * это не отражает реальность и только исказило бы голосование.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCDepartmentImportService {

    private static final String CRM_COMMENT_MARKER = "Заявка на производство";

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;

    public ImportResult importAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }

        Map<String, String> departmentByDocument = new HashMap<>();
        for (OneCClient.ProductionReportHeader header : oneCClient.fetchProductionReportHeaders()) {
            if (header.comment() != null && header.comment().contains(CRM_COMMENT_MARKER)) {
                continue; // создан CRM — подразделение там не показательно, см. class-javadoc
            }
            departmentByDocument.put(header.ref(), header.departmentKey());
        }

        Map<String, Map<String, Integer>> votesByNomenclature = new HashMap<>();
        for (OneCClient.ProductionReportLine line : oneCClient.fetchProductionReportLines()) {
            String department = departmentByDocument.get(line.parentRef());
            if (department == null || department.isBlank()) continue;
            votesByNomenclature
                    .computeIfAbsent(line.nomenclatureKey(), k -> new HashMap<>())
                    .merge(department, 1, Integer::sum);
        }

        Map<String, String> departmentByNomenclature = new HashMap<>();
        votesByNomenclature.forEach((nomenclatureKey, votes) -> {
            String best = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> vote : votes.entrySet()) {
                if (vote.getValue() > bestCount) {
                    best = vote.getKey();
                    bestCount = vote.getValue();
                }
            }
            departmentByNomenclature.put(nomenclatureKey, best);
        });

        int updated = 0;
        int skippedNoOneCMatch = 0;
        int skippedNoHistory = 0;
        for (Product product : productRepository.findAll()) {
            if (product.getOneCNomenclatureKey() == null) {
                skippedNoOneCMatch++;
                continue;
            }
            String department = departmentByNomenclature.get(product.getOneCNomenclatureKey());
            if (department == null) {
                skippedNoHistory++;
                continue;
            }
            if (!department.equals(product.getOneCDepartmentKey())) {
                product.setOneCDepartmentKey(department);
                productRepository.save(product);
                updated++;
            }
        }

        log.info("Импорт подразделений из 1С: обновлено {}, без связи с 1С {}, без истории документов {}",
                updated, skippedNoOneCMatch, skippedNoHistory);
        return new ImportResult(updated, skippedNoOneCMatch, skippedNoHistory);
    }

    public record ImportResult(int updated, int skippedNoOneCMatch, int skippedNoHistory) {
    }
}
