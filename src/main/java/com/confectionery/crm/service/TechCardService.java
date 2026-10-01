package com.confectionery.crm.service;

import com.confectionery.crm.api.dto.TechCardDto;
import com.confectionery.crm.api.dto.TechCardRequest;
import com.confectionery.crm.api.dto.TechCardSummaryDto;
import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.PurchaseItem;
import com.confectionery.crm.domain.Recipe;
import com.confectionery.crm.domain.RecipeItem;
import com.confectionery.crm.domain.TechCard;
import com.confectionery.crm.domain.TechCardStage;
import com.confectionery.crm.repository.PurchaseRepository;
import com.confectionery.crm.repository.RecipeRepository;
import com.confectionery.crm.repository.TechCardRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Техкарты для экрана «Производство» — только товары с рецептурой (без неё карте не из чего считать). */
@Service
@RequiredArgsConstructor
public class TechCardService {

    private final ProductService productService;
    private final RecipeRepository recipeRepository;
    private final TechCardRepository techCardRepository;
    private final PurchaseRepository purchaseRepository;

    @Transactional(readOnly = true)
    public List<TechCardSummaryDto> list() {
        Map<Long, TechCard> cards = techCardRepository.findAll().stream()
                .collect(Collectors.toMap(c -> c.getProduct().getId(), Function.identity()));
        return recipeRepository.findAll().stream()
                .filter(r -> r.getProduct().isActive())
                .map(r -> {
                    Product p = r.getProduct();
                    TechCard card = cards.get(p.getId());
                    List<TechCardStage> stages = card == null ? List.of() : card.getStages();
                    return new TechCardSummaryDto(p.getId(), p.getName(), p.getGroup(), p.getUnit(), p.getImageUrl(),
                            p.isSemiFinished(), r.getYieldQuantity(), r.getItems().size(), stages.size(),
                            (int) stages.stream().filter(TechCardStage::isCcp).count(), isDraft(card));
                })
                .sorted((a, b) -> a.name().compareToIgnoreCase(b.name()))
                .toList();
    }

    @Transactional(readOnly = true)
    public TechCardDto get(Long productId) {
        Product product = productService.findById(productId);
        Recipe recipe = recipeRepository.findByProductId(productId).orElse(null);
        TechCard card = techCardRepository.findByProductId(productId).orElseGet(TechCard::new);
        List<RecipeItem> recipeItems = recipe == null ? List.of() : recipe.getItems();

        Map<Long, PurchaseItem> lastPurchase = new HashMap<>();
        List<Long> ingredientIds = recipeItems.stream().map(i -> i.getIngredient().getId()).toList();
        if (!ingredientIds.isEmpty()) {
            for (PurchaseItem pi : purchaseRepository.findPricedItemsNewestFirst(ingredientIds)) {
                lastPurchase.putIfAbsent(pi.getIngredient().getId(), pi);
            }
        }

        List<TechCardDto.Item> items = recipeItems.stream().map(ri -> {
            PurchaseItem pi = lastPurchase.get(ri.getIngredient().getId());
            BigDecimal unitPrice = pi == null ? null : pi.getTotalPrice().divide(pi.getQuantity(), 2, RoundingMode.HALF_UP);
            return new TechCardDto.Item(ri.getIngredient().getId(), ri.getIngredient().getName(), ri.getIngredient().getUnit(),
                    ri.getQuantity(), unitPrice, pi == null ? null : pi.getPurchase().getPurchaseDate());
        }).toList();

        List<TechCardDto.Stage> stages = card.getStages().stream()
                .map(s -> new TechCardDto.Stage(s.getTitle(), s.getDescription(), s.getTemperature(), s.getDuration(), s.isCcp()))
                .toList();

        return new TechCardDto(product.getId(), product.getName(), product.getGroup(), product.getUnit(),
                product.getImageUrl(), product.getNetWeightGrams(), product.getPrice(),
                recipe == null ? BigDecimal.ONE : recipe.getYieldQuantity(), items,
                card.getShelfLife(), card.getStorageConditions(), card.getPackaging(),
                card.getQualityLook(), card.getQualityColor(), card.getQualityCrumb(), card.getQualityTaste(), stages, isDraft(card));
    }

    @Transactional
    public TechCardDto save(Long productId, TechCardRequest req) {
        Product product = productService.findById(productId);
        TechCard card = techCardRepository.findByProductId(productId).orElseGet(() -> {
            TechCard c = new TechCard();
            c.setProduct(product);
            return c;
        });
        card.setDraft(false);
        card.setShelfLife(blankToNull(req.shelfLife()));
        card.setStorageConditions(blankToNull(req.storageConditions()));
        card.setPackaging(blankToNull(req.packaging()));
        card.setQualityLook(blankToNull(req.qualityLook()));
        card.setQualityColor(blankToNull(req.qualityColor()));
        card.setQualityCrumb(blankToNull(req.qualityCrumb()));
        card.setQualityTaste(blankToNull(req.qualityTaste()));

        card.getStages().clear();
        List<TechCardDto.Stage> stages = req.stages() == null ? List.of() : req.stages();
        int position = 0;
        for (TechCardDto.Stage s : stages) {
            if (s.title() == null || s.title().isBlank()) continue;
            TechCardStage stage = new TechCardStage();
            stage.setTechCard(card);
            stage.setPosition(position++);
            stage.setTitle(s.title().trim());
            stage.setDescription(blankToNull(s.description()));
            stage.setTemperature(blankToNull(s.temperature()));
            stage.setDuration(blankToNull(s.duration()));
            stage.setCcp(s.ccp());
            card.getStages().add(stage);
        }
        techCardRepository.save(card);
        return get(productId);
    }

    private static boolean isDraft(TechCard card) {
        return card != null && Boolean.TRUE.equals(card.getDraft());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
