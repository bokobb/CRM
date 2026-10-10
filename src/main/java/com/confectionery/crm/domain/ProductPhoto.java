package com.confectionery.crm.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Фото товара для альбома на сайте (templates/promo.html, магазин /shop): сколько угодно на товар,
 * порядок задаёт админ, первое — обложка, его же дублирует {@link Product#getImageUrl()}
 * (заказы, техкарты и старые версии приложения смотрят только туда). См. ProductPhotoService.
 */
@Entity
@Table(name = "product_photos")
@Getter
@Setter
@NoArgsConstructor
public class ProductPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 2000)
    private String url;

    @Column(nullable = false)
    private int position;

    /** Размер в пикселях — альбом раскладывает страницу до загрузки фото. null, если не прочитался (webp/gif). */
    private Integer width;

    private Integer height;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /**
     * Когда фото прошло выравнивание (scripts/foto: товар по центру, единый фон) — заменено
     * обработанным или осознанно оставлено как есть. null — ещё не обрабатывалось; по этому полю
     * scripts/foto/foto.sh берёт только новые обложки.
     */
    private LocalDateTime retouchedAt;
}
