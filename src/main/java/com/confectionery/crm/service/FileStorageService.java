package com.confectionery.crm.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;

/** Хранит загруженные с телефона/компьютера фото товаров на диске (см. crm.uploads.dir). */
@Service
public class FileStorageService {

    private static final Map<String, String> ALLOWED_TYPES = Map.of(
            "image/jpeg", "jpg",
            // "image/jpg" не по стандарту (правильно image/jpeg), но некоторые браузеры/телефоны
            // всё равно шлют именно так — принимаем оба варианта, иначе jpg-фото отклоняются.
            "image/jpg", "jpg",
            "image/png", "png",
            "image/webp", "webp",
            "image/gif", "gif"
    );

    private final Path root;

    public FileStorageService(@Value("${crm.uploads.dir}") String uploadsDir) {
        this.root = Path.of(uploadsDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать папку для фото товаров: " + root, e);
        }
    }

    /** Сохраняет файл на диск и возвращает относительный URL для раздачи (см. UploadConfig). */
    public String storeProductImage(Long productId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Пустой файл");
        }
        String ext = ALLOWED_TYPES.get(file.getContentType());
        if (ext == null) {
            throw new IllegalArgumentException("Поддерживаются только изображения (jpeg, png, webp, gif)");
        }
        String filename = "product-" + productId + "-" + System.currentTimeMillis() + "." + ext;
        try {
            Files.copy(file.getInputStream(), root.resolve(filename), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл", e);
        }
        return "/product-images/" + filename;
    }
}
