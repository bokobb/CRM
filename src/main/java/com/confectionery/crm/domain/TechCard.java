package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * Технологическая карта товара — то, чего нет в рецептуре (Recipe): этапы техпроцесса с
 * контрольными точками, органолептика, срок годности, хранение, упаковка. Сырьё и выход на замес
 * берутся из рецептуры, здесь не дублируются. Экран «Производство» в мобильном приложении.
 * Создаётся лениво при первом сохранении — у товара без неё карта показывается пустой.
 */
@Entity
@Table(name = "tech_cards")
@Getter
@Setter
@NoArgsConstructor
public class TechCard {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false, unique = true)
    private Product product;

    private String shelfLife;

    @Column(length = 500)
    private String storageConditions;

    @Column(length = 500)
    private String packaging;

    @Column(length = 1000)
    private String qualityLook;

    @Column(length = 1000)
    private String qualityColor;

    @Column(length = 1000)
    private String qualityCrumb;

    @Column(length = 1000)
    private String qualityTaste;

    /**
     * Черновик — заполнено типовыми значениями (загрузка 01.10.2026), технолог ещё не проверил.
     * Снимается при сохранении карты из приложения. null в старых строках = не черновик.
     */
    private Boolean draft;

    @OneToMany(mappedBy = "techCard", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    private List<TechCardStage> stages = new ArrayList<>();
}
