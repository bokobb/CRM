package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Разовый (по кнопке) импорт ТОЛЬКО GUID-а "верхней" спецификации ({@link Product#getOneCSpecificationKey()})
 * — используется в поле "Спецификация_Key" строк "Продукция" отчёта производства (см.
 * OneCProductionSyncService). В отличие от {@link OneCRecipeImportService#importAll()}, здесь
 * состав рецептов (Recipe/RecipeItem) НЕ трогается вообще — если рецепты в CRM правились вручную
 * после первоначальной настройки, это их не затронет. Та же логика выбора "верхней" спецификации
 * (см. {@link OneCRecipeImportService#pickTopSpec}), просто без разворачивания в плоский список
 * сырья и без записи в Recipe.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OneCSpecificationImportService {

    private final OneCClient oneCClient;
    private final ProductRepository productRepository;

    public ImportResult importAll() {
        if (!oneCClient.isConfigured()) {
            throw new IllegalStateException("Подключение к 1С не настроено (crm.onec.base-url/username/password)");
        }

        Map<String, String> names = oneCClient.fetchNomenclatureNames();
        Map<String, List<OneCClient.Specification>> byOwner = oneCClient.fetchSpecifications().stream()
                .collect(Collectors.groupingBy(OneCClient.Specification::ownerKey));

        Map<String, String> keyByExactName = new HashMap<>();
        for (Map.Entry<String, String> e : names.entrySet()) {
            keyByExactName.putIfAbsent(e.getValue().trim().toLowerCase(), e.getKey());
        }

        int updated = 0;
        int skippedNoOneCMatch = 0;
        int skippedNoSpec = 0;
        for (Product product : productRepository.findAll()) {
            String key = keyByExactName.get(product.getName().trim().toLowerCase());
            if (key == null) {
                skippedNoOneCMatch++;
                continue;
            }
            if (!byOwner.containsKey(key)) {
                skippedNoSpec++;
                continue;
            }
            OneCClient.Specification top = OneCRecipeImportService.pickTopSpec(key, byOwner);
            if (!top.ref().equals(product.getOneCSpecificationKey())) {
                product.setOneCSpecificationKey(top.ref());
                productRepository.save(product);
                updated++;
            }
        }

        log.info("Импорт спецификаций из 1С: обновлено {}, без спецификации в 1С {}, не найдено по названию {}",
                updated, skippedNoSpec, skippedNoOneCMatch);
        return new ImportResult(updated, skippedNoOneCMatch, skippedNoSpec);
    }

    public record ImportResult(int updated, int skippedNoOneCMatch, int skippedNoSpec) {
    }
}
