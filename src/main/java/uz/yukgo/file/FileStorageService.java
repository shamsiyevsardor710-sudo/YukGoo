package uz.yukgo.file;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.common.ApiException;
import uz.yukgo.config.AppProperties;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/** Rasmlarni diskka saqlaydi va /uploads/... manzilini qaytaradi. */
@Slf4j
@Service
public class FileStorageService {
    private static final Map<String, String> ALLOWED = Map.of(
            "image/jpeg", ".jpg", "image/png", ".png", "image/webp", ".webp");

    private final Path root;

    public FileStorageService(AppProperties props) throws IOException {
        this.root = Path.of(props.uploadsDir()).toAbsolutePath().normalize();
        Files.createDirectories(root);
    }

    public String storeImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) throw ApiException.badRequest("Fayl tanlanmagan");
        String ext = file.getContentType() == null ? null : ALLOWED.get(file.getContentType());
        if (ext == null) throw ApiException.badRequest("Faqat JPG, PNG yoki WEBP rasm yuklash mumkin");
        try (InputStream in = file.getInputStream()) {
            byte[] head = in.readNBytes(12);
            if (!looksLikeImage(head)) throw ApiException.badRequest("Fayl rasm emas");
            Path dir = root.resolve(folder).normalize();
            if (!dir.startsWith(root)) throw ApiException.badRequest("Noto'g'ri papka");
            Files.createDirectories(dir);
            String name = UUID.randomUUID() + ext;
            try (InputStream full = file.getInputStream()) {
                Files.copy(full, dir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
            return "/uploads/" + folder + "/" + name;
        } catch (IOException e) {
            log.error("Faylni saqlab bo'lmadi", e);
            throw ApiException.badRequest("Faylni saqlab bo'lmadi");
        }
    }

    public void delete(String url) {
        if (url == null || !url.startsWith("/uploads/")) return;
        try {
            Path p = root.resolve(url.substring("/uploads/".length())).normalize();
            if (p.startsWith(root)) Files.deleteIfExists(p);
        } catch (IOException ignored) {
        }
    }

    private boolean looksLikeImage(byte[] h) {
        if (h.length < 4) return false;
        boolean jpg = (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8;
        boolean png = (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G';
        boolean webp = h.length >= 12 && h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F'
                && h[8] == 'W' && h[9] == 'E' && h[10] == 'B' && h[11] == 'P';
        return jpg || png || webp;
    }
}
