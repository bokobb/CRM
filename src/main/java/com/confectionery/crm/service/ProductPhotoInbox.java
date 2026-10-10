package com.confectionery.crm.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Папка на сервере, через которую scripts/foto/foto.sh меняет фото альбома тем же сервисом, что
 * и загрузка из CRM (варианты, запись в базе, удаление старого файла — штатно). Доступ к ней —
 * только по ssh к серверу, то есть у того, кто и так администрирует CRM. Имя файла
 * «товар-фото.расширение»:
 * <ul>
 *   <li>{@code 24-131.png} (jpg, webp) — заменить фото 131 товара 24 этим файлом на том же месте;</li>
 *   <li>{@code 24-131.skip} — фото обработано, но оставлено как было;</li>
 *   <li>{@code 24-131.delete} — удалить фото из альбома.</li>
 * </ul>
 * Выполненное удаляется из папки, ошибка — файл уезжает в failed/ рядом с .txt-причиной.
 */
@Slf4j
@Service
public class ProductPhotoInbox {

    private static final Pattern NAME = Pattern.compile("(\\d+)-(\\d+)\\.(png|jpg|jpeg|webp|skip|delete)");
    private static final Map<String, String> TYPES = Map.of("png", "image/png", "jpg", "image/jpeg",
            "jpeg", "image/jpeg", "webp", "image/webp");

    private final Path dir;
    private final ProductPhotoService productPhotoService;

    public ProductPhotoInbox(@Value("${crm.photo-inbox.dir:./data/photo-inbox}") String dir,
                             ProductPhotoService productPhotoService) {
        this.dir = Path.of(dir).toAbsolutePath().normalize();
        this.productPhotoService = productPhotoService;
    }

    @Scheduled(fixedDelay = 20_000, initialDelay = 30_000)
    public void process() {
        if (!Files.isDirectory(dir)) {
            return;
        }
        List<Path> files;
        try (Stream<Path> s = Files.list(dir)) {
            files = s.filter(Files::isRegularFile).sorted().toList();
        } catch (IOException e) {
            log.warn("Папка фото {}: {}", dir, e.getMessage());
            return;
        }
        for (Path file : files) {
            Matcher m = NAME.matcher(file.getFileName().toString());
            if (!m.matches()) {
                continue; // недокачанный .part и прочее — не наше
            }
            Long productId = Long.valueOf(m.group(1)), photoId = Long.valueOf(m.group(2));
            String kind = m.group(3);
            try {
                switch (kind) {
                    case "skip" -> productPhotoService.markRetouched(productId, photoId);
                    case "delete" -> productPhotoService.delete(productId, photoId);
                    default -> productPhotoService.replace(productId, photoId,
                            new FileUpload(file.getFileName().toString(), TYPES.get(kind), Files.readAllBytes(file)));
                }
                Files.delete(file);
                log.info("Фото товара {}: {} ({})", productId, kind, file.getFileName());
            } catch (Exception e) {
                log.warn("Фото товара {} — {} не выполнено: {}", productId, file.getFileName(), e.getMessage());
                try {
                    Path failed = Files.createDirectories(dir.resolve("failed"));
                    Files.move(file, failed.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                    Files.writeString(failed.resolve(file.getFileName() + ".txt"), String.valueOf(e.getMessage()));
                } catch (IOException ignored) {
                    // останется в папке и повторится на следующем проходе
                }
            }
        }
    }

    /** Файл с диска в виде загрузки — FileStorageService принимает только MultipartFile. */
    private record FileUpload(String name, String type, byte[] bytes) implements MultipartFile {
        @Override public String getName() { return "file"; }
        @Override public String getOriginalFilename() { return name; }
        @Override public String getContentType() { return type; }
        @Override public boolean isEmpty() { return bytes.length == 0; }
        @Override public long getSize() { return bytes.length; }
        @Override public byte[] getBytes() { return bytes; }
        @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        @Override public void transferTo(File dest) throws IOException { Files.write(dest.toPath(), bytes); }
    }
}
