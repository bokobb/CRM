package com.confectionery.crm.service;

import com.confectionery.crm.domain.Product;
import com.confectionery.crm.domain.ProductPhoto;
import com.confectionery.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Альбом фото товара: добавление (сколько угодно), удаление, порядок. Первое фото — обложка,
 * его адрес всегда копируется в {@link Product#setImageUrl}, чтобы всё, что знает только об одном
 * фото (заказы, техкарты, накладные, старые версии приложения), продолжало работать.
 * Права (только ADMIN) проверяются в ProductApiController / ProductController.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ProductPhotoService {

    private final ProductService productService;
    private final ProductRepository productRepository;
    private final FileStorageService fileStorageService;

    /** Новые фото — в конец альбома (у товара без фото первое станет обложкой). */
    @Transactional
    public Product add(Long productId, List<MultipartFile> files) {
        Product product = productService.findById(productId);
        if (files == null || files.stream().allMatch(MultipartFile::isEmpty)) {
            throw new IllegalArgumentException("Не выбрано ни одного фото");
        }
        List<FileStorageService.StoredImage> stored = new ArrayList<>();
        try {
            for (MultipartFile file : files) {
                if (!file.isEmpty()) {
                    stored.add(fileStorageService.storeProductImage(productId, file));
                }
            }
        } catch (RuntimeException e) {
            stored.forEach(s -> fileStorageService.deleteProductImage(s.url()));
            throw e;
        }
        stored.forEach(s -> product.getPhotos().add(photo(product, s.url(), s.width(), s.height())));
        renumber(product);
        return productRepository.save(product);
    }

    /**
     * Старый способ «одно фото на товар» (приложение до альбома, веб-форма CRM): новое фото
     * заменяет обложку, остальные фото альбома остаются.
     */
    @Transactional
    public Product replaceCover(Long productId, MultipartFile file) {
        Product product = productService.findById(productId);
        FileStorageService.StoredImage s = fileStorageService.storeProductImage(productId, file);
        if (!product.getPhotos().isEmpty()) {
            ProductPhoto cover = product.getPhotos().remove(0);
            fileStorageService.deleteProductImage(cover.getUrl());
        }
        product.getPhotos().add(0, photo(product, s.url(), s.width(), s.height()));
        renumber(product);
        return productRepository.save(product);
    }

    /**
     * Новый файл вместо фото на том же месте альбома: id, позиция (обложка остаётся обложкой) и
     * дата добавления сохраняются, старый файл удаляется. Для выравнивания фото (scripts/foto).
     */
    @Transactional
    public Product replace(Long productId, Long photoId, MultipartFile file) {
        Product product = productService.findById(productId);
        ProductPhoto photo = find(product, photoId);
        FileStorageService.StoredImage s = fileStorageService.storeProductImage(productId, file);
        String old = photo.getUrl();
        photo.setUrl(s.url());
        photo.setWidth(s.width());
        photo.setHeight(s.height());
        photo.setRetouchedAt(LocalDateTime.now());
        renumber(product);
        Product saved = productRepository.save(product);
        fileStorageService.deleteProductImage(old);
        return saved;
    }

    /** Фото обработано, но оставлено как было (программа не уверена) — второй раз не брать. */
    @Transactional
    public Product markRetouched(Long productId, Long photoId) {
        Product product = productService.findById(productId);
        find(product, photoId).setRetouchedAt(LocalDateTime.now());
        return productRepository.save(product);
    }

    @Transactional
    public Product delete(Long productId, Long photoId) {
        Product product = productService.findById(productId);
        ProductPhoto photo = find(product, photoId);
        product.getPhotos().remove(photo);
        fileStorageService.deleteProductImage(photo.getUrl());
        renumber(product);
        return productRepository.save(product);
    }

    /** ids — все фото товара в новом порядке, первое станет обложкой. */
    @Transactional
    public Product reorder(Long productId, List<Long> ids) {
        Product product = productService.findById(productId);
        Map<Long, ProductPhoto> byId = product.getPhotos().stream()
                .collect(Collectors.toMap(ProductPhoto::getId, Function.identity()));
        if (ids == null || ids.size() != byId.size() || !byId.keySet().equals(new HashSet<>(ids))) {
            throw new IllegalArgumentException("Список фото устарел — обновите карточку товара");
        }
        List<ProductPhoto> ordered = ids.stream().map(byId::get).toList();
        product.getPhotos().clear();
        product.getPhotos().addAll(ordered);
        renumber(product);
        return productRepository.save(product);
    }

    /**
     * До альбома у товара было одно фото в imageUrl — переносим его в альбом обложкой. Один раз на
     * товар (повторно не срабатывает: у товара уже есть фото), поэтому безопасно при каждом запуске.
     */
    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void moveSinglePhotosToAlbum() {
        int moved = 0;
        for (Product product : productRepository.findAll()) {
            String url = product.getImageUrl();
            if (url == null || url.isBlank() || !product.getPhotos().isEmpty()) {
                continue;
            }
            product.getPhotos().add(photo(product, url.trim(), null, null));
            moved++;
        }
        if (moved > 0) {
            log.info("Альбом товаров: перенесено фото из imageUrl — {}", moved);
        }
    }

    private static ProductPhoto find(Product product, Long photoId) {
        return product.getPhotos().stream().filter(p -> p.getId().equals(photoId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Фото не найдено"));
    }

    private static ProductPhoto photo(Product product, String url, Integer width, Integer height) {
        ProductPhoto p = new ProductPhoto();
        p.setProduct(product);
        p.setUrl(url);
        p.setWidth(width);
        p.setHeight(height);
        return p;
    }

    private static void renumber(Product product) {
        List<ProductPhoto> photos = product.getPhotos();
        for (int i = 0; i < photos.size(); i++) {
            photos.get(i).setPosition(i);
        }
        product.setImageUrl(photos.isEmpty() ? null : photos.get(0).getUrl());
    }
}
