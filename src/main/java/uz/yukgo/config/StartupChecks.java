package uz.yukgo.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Ishga tushganda xavfli sozlamalarni logga yozadi. Asosiysi: Railway'da baza yoki rasmlar konteyner ichida qolib
 * ketgani — Railway har deployda konteynerni yangidan yaratadi, Volume'dan tashqaridagi fayllar o'chadi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StartupChecks {
    private final Environment env;
    private final AppProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        for (String w : warnings(System.getenv(), env.getProperty("spring.datasource.url", ""), props.uploadsDir())) {
            log.error(w);
        }
    }

    static List<String> warnings(Map<String, String> sysEnv, String dbUrl, String uploadsDir) {
        List<String> out = new ArrayList<>();
        boolean railway = sysEnv.keySet().stream().anyMatch(k -> k.startsWith("RAILWAY_"));
        if (!railway) return out;
        String volume = sysEnv.get("RAILWAY_VOLUME_MOUNT_PATH");
        if (dbUrl != null && dbUrl.startsWith("jdbc:h2:file:")) {
            String file = dbUrl.substring("jdbc:h2:file:".length()).split(";")[0];
            if (!onVolume(file, volume)) {
                out.add("DIQQAT: baza konteyner ichidagi H2 faylida (" + file + "). Railway har deployda konteynerni yangidan"
                        + " yaratadi — foydalanuvchilar, buyurtmalar va boshqa ma'lumotlar o'chib ketadi. PostgreSQL ulang:"
                        + " SPRING_PROFILES_ACTIVE=prod, DB_URL, DB_USER, DB_PASSWORD (README, \"Railway'ga joylash\").");
            }
        }
        if (uploadsDir != null && !uploadsDir.isBlank() && !onVolume(uploadsDir, volume)) {
            out.add("DIQQAT: rasmlar konteyner ichida saqlanmoqda (" + absolute(uploadsDir) + ") va har deployda o'chadi."
                    + " Xizmatga shu manzil bilan Volume ulang va RAILWAY_RUN_UID=0 o'zgaruvchisini qo'ying"
                    + " (README, \"Railway'ga joylash\").");
        }
        return out;
    }

    static boolean onVolume(String path, String volumeMount) {
        if (path == null || volumeMount == null || volumeMount.isBlank()) return false;
        return absolute(path).startsWith(absolute(volumeMount));
    }

    private static Path absolute(String p) {
        return Path.of(p).toAbsolutePath().normalize();
    }
}
