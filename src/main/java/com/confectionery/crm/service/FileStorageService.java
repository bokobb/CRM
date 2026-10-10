package com.confectionery.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
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

    /** Сохранённый файл: относительный URL для раздачи (см. UploadConfig) и размер, если удалось прочитать. */
    public record StoredImage(String url, Integer width, Integer height) {}

    /** Фото товара для альбома (см. ProductPhotoService) — приводится к 3:4, см. {@link #toPortrait}. */
    public StoredImage storeProductImage(Long productId, MultipartFile file) {
        return store("product", "/product-images", productId, file, true);
    }

    /** Фото накладной поставщика, приложенное к закупке сырья (см. PurchaseService.attachImage). */
    public String storePurchaseInvoiceImage(Long purchaseId, MultipartFile file) {
        return store("purchase-invoice", "/purchase-images", purchaseId, file, false).url();
    }

    /** Удаляет загруженное фото товара с диска; внешние ссылки и чужие папки не трогает. */
    public void deleteProductImage(String url) {
        String prefix = "/product-images/";
        if (url == null || !url.startsWith(prefix)) {
            return;
        }
        Path file = root.resolve(url.substring(prefix.length())).normalize();
        if (file.startsWith(root)) {
            deleteQuietly(file);
        }
    }

    private StoredImage store(String filenamePrefix, String urlPrefix, Long entityId, MultipartFile file, boolean productPhoto) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Пустой файл");
        }
        String ext = ALLOWED_TYPES.get(file.getContentType());
        if (ext == null) {
            throw new IllegalArgumentException("Поддерживаются только изображения (jpeg, png, webp, gif)");
        }
        String filename = filenamePrefix + "-" + entityId + "-" + System.currentTimeMillis() + "-"
                + Integer.toHexString(java.util.concurrent.ThreadLocalRandom.current().nextInt()) + "." + ext;
        Path target = root.resolve(filename);
        try {
            Files.copy(file.getInputStream(), target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось сохранить файл", e);
        }

        Integer width = null, height = null;
        if (productPhoto && CONVERT_TO_WEBP.contains(ext)) {
            Path portrait = toPortrait(target);
            if (portrait != null) {
                target = portrait;
                ext = "png";
                filename = portrait.getFileName().toString();
                width = PRODUCT_IMAGE_WIDTH;
                height = PRODUCT_IMAGE_HEIGHT;
            }
        }

        if (CONVERT_TO_WEBP.contains(ext)) {
            Path converted = tryConvertToWebp(target);
            if (converted != null) {
                filename = converted.getFileName().toString();
            }
        }
        return new StoredImage(urlPrefix + "/" + filename, width, height);
    }

    /** Фото товара 3:4 — как карточки в каталоге сайта (templates/promo.html) и альбом. */
    private static final int PRODUCT_IMAGE_WIDTH = 900;
    private static final int PRODUCT_IMAGE_HEIGHT = 1200;
    /** Меньше — фото мутное в каталоге; приложение проверяет то же самое ещё до загрузки (ImageCropScreen). */
    private static final int PRODUCT_IMAGE_MIN_SIDE = 600;

    /**
     * Фото товара → {@link #PRODUCT_IMAGE_WIDTH}×{@link #PRODUCT_IMAGE_HEIGHT} PNG рядом с исходником:
     * не 3:4 дополняется белыми полями (без обрезки — товар не отрежется). Приложение уже
     * присылает обрезанное 3:4, это страховка для веб-формы и старых версий. Мелкое фото
     * отклоняется. Не прочиталось (экзотический формат) — null, файл остаётся как есть.
     */
    private Path toPortrait(Path source) {
        BufferedImage src;
        try {
            src = ImageIO.read(source.toFile());
        } catch (IOException e) {
            return null;
        }
        if (src == null) {
            return null;
        }
        int w = src.getWidth(), h = src.getHeight();
        if (Math.min(w, h) < PRODUCT_IMAGE_MIN_SIDE) {
            deleteQuietly(source);
            throw new IllegalArgumentException("Фото слишком маленькое (" + w + "×" + h + "), нужно не меньше "
                    + PRODUCT_IMAGE_MIN_SIDE + " px по каждой стороне");
        }
        int ow = PRODUCT_IMAGE_WIDTH, oh = PRODUCT_IMAGE_HEIGHT;
        double scale = Math.min((double) ow / w, (double) oh / h);
        int dw = (int) Math.round(w * scale), dh = (int) Math.round(h * scale);
        BufferedImage out = new BufferedImage(ow, oh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, ow, oh);
        g.drawImage(src, (ow - dw) / 2, (oh - dh) / 2, dw, dh, null);
        g.dispose();
        String name = source.getFileName().toString();
        int dot = name.lastIndexOf('.');
        Path target = source.resolveSibling((dot < 0 ? name : name.substring(0, dot)) + "-34.png");
        try {
            ImageIO.write(out, "png", target.toFile());
        } catch (IOException e) {
            log.warn("Не удалось привести фото к 3:4: {}", e.getMessage());
            return null;
        }
        deleteQuietly(source);
        return target;
    }

    private static void deleteQuietly(Path path) {
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // мусорный исходник на диске не критичен
        }
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
