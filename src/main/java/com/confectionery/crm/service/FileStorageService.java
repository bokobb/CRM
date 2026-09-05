package com.confectionery.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Хранит загруженные с телефона/компьютера фото товаров на диске (см. crm.uploads.dir). */
@Slf4j
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

    /**
     * Перекодируем в WebP при загрузке — на реальных фото товаров экономия ~85-90% размера
     * (PNG 388КБ -> WebP 47КБ на тесте). GIF сознательно не трогаем — он может быть
     * анимированным, а cwebp сохранил бы только первый кадр.
     */
    private static final Set<String> CONVERT_TO_WEBP = Set.of("jpg", "png");

    private final Path root;
    private final String cwebpPath;

    public FileStorageService(@Value("${crm.uploads.dir}") String uploadsDir,
                               @Value("${crm.uploads.cwebp-path:/home/b-o-k-obb/tools/cwebp}") String cwebpPath) {
        this.root = Path.of(uploadsDir).toAbsolutePath().normalize();
        this.cwebpPath = cwebpPath;
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось создать папку для фото товаров: " + root, e);
        }
    }

    /** Сохраняет файл на диск и возвращает относительный URL для раздачи (см. UploadConfig). */
    public String storeProductImage(Long productId, MultipartFile file) {
        return store("product", "/product-images", productId, file);
    }

    /** Фото накладной поставщика, приложенное к закупке сырья (см. PurchaseService.attachImage). */
    public String storePurchaseInvoiceImage(Long purchaseId, MultipartFile file) {
        return store("purchase-invoice", "/purchase-images", purchaseId, file);
    }

    private String store(String filenamePrefix, String urlPrefix, Long entityId, MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Пустой файл");
        }
        String ext = ALLOWED_TYPES.get(file.getContentType());
        if (ext == null) {
            throw new IllegalArgumentException("Поддерживаются только изображения (jpeg, png, webp, gif)");
        }
        String filename = filenamePrefix + "-" + entityId + "-" + System.currentTimeMillis() + "." + ext;
        Path target = root.resolve(filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл", e);
        }

        if (CONVERT_TO_WEBP.contains(ext)) {
            Path converted = tryConvertToWebp(target);
            if (converted != null) {
                filename = converted.getFileName().toString();
            }
        }
        return urlPrefix + "/" + filename;
    }

    /**
     * Перекодирует файл в WebP тем же путём (расширение меняется на .webp), исходник
     * удаляется. При любой проблеме (cwebp недоступен, упал и т.п.) — тихо оставляем
     * исходный формат, загрузка фото не должна ломаться из-за необязательной оптимизации.
     */
    private Path tryConvertToWebp(Path source) {
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = source.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + ".webp");
        try {
            Process process = new ProcessBuilder(cwebpPath, "-quiet", "-q", "82",
                    source.toString(), "-o", target.toString())
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished || process.exitValue() != 0 || !Files.exists(target)) {
                log.warn("Конвертация {} в WebP не удалась — оставляю исходный формат", name);
                Files.deleteIfExists(target);
                return null;
            }
            Files.delete(source);
            return target;
        } catch (IOException e) {
            log.warn("Не удалось запустить cwebp для {}: {}", name, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
