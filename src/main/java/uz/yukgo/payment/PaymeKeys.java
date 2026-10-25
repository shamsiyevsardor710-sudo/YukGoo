package uz.yukgo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import uz.yukgo.config.AppSetting;
import uz.yukgo.config.AppSettingRepository;

import java.time.Instant;

/**
 * Payme kassa kalitini tekshirish. Asosiy kalit — Railway'dagi PAYME_KEY. Kassa kabinetida kalit almashtirilsa,
 * Payme ChangePassword so'rovini yuboradi: yangi kalitning xeshi bazaga yoziladi va shu PAYME_KEY turgan paytda
 * u ishlatiladi. Railway'da PAYME_KEY ning o'zi o'zgartirilsa — yana o'sha (yangi) qiymat amal qiladi.
 * Bazada kalitning o'zi emas, faqat SHA-256 xeshi saqlanadi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymeKeys {
    static final String SETTING = "payment.payme.key";

    private final PaymentSettings settings;
    private final AppSettingRepository repo;

    /** Payme yuborgan kalit to'g'rimi */
    boolean matches(String candidate) {
        String env = settings.paymeKey();
        if (env.isEmpty() || candidate == null) return false;
        String stored = repo.findById(SETTING).map(AppSetting::getValue).orElse(null);
        if (stored != null) {
            int sep = stored.indexOf(':');
            // Saqlangan kalit hozirgi PAYME_KEY davrida o'rnatilgan bo'lsa — o'sha amal qiladi
            if (sep > 0 && stored.substring(0, sep).equals(ProviderAuth.sha256Hex(env))) {
                return ProviderAuth.same(ProviderAuth.sha256Hex(candidate), stored.substring(sep + 1));
            }
        }
        return ProviderAuth.same(candidate, env);
    }

    /** ChangePassword: kabinetda o'rnatilgan yangi kalit */
    void change(String newKey) {
        AppSetting s = repo.findById(SETTING).orElseGet(() -> new AppSetting(SETTING, null));
        s.setValue(ProviderAuth.sha256Hex(settings.paymeKey()) + ":" + ProviderAuth.sha256Hex(newKey));
        s.setUpdatedAt(Instant.now());
        repo.save(s);
        log.warn("Payme kassa kaliti almashtirildi (ChangePassword). Railway'dagi PAYME_KEY ni ham yangi kalitga almashtiring.");
    }
}
