package uz.yukgo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.yukgo.config.AppProperties;

import java.util.ArrayList;
import java.util.List;

/** Ishga tushganda to'lov sozlamalarini tekshiradi va logga yozadi (kalitlarning o'zi hech qachon yozilmaydi). */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaymentStartupCheck {
    private final PaymentSettings settings;
    private final AppProperties props;

    @EventListener(ApplicationReadyEvent.class)
    public void check() {
        if (!settings.anyEnabled() && !settings.paynetCredentials()) return;
        log.info("To'lov tizimlari: Click {}, Payme {}{}, Paynet {}", on(settings.clickEnabled()), on(settings.paymeEnabled()),
                settings.paymeEnabled() && settings.paymeTest() ? " (sinov kassasi)" : "", on(settings.paynetEnabled()));
        for (String w : warnings(settings.clickEnabled() || settings.paymeEnabled(), settings.paynetCredentials(),
                !settings.paynetServiceIds().isEmpty(), props.publicUrl())) {
            log.warn(w);
        }
    }

    /** @param paynetCredentials PAYNET_USERNAME va PAYNET_PASSWORD berilgan */
    static List<String> warnings(boolean redirects, boolean paynetCredentials, boolean paynetServiceSet, String publicUrl) {
        List<String> out = new ArrayList<>();
        if (redirects && (publicUrl == null || !publicUrl.trim().startsWith("https://"))) {
            out.add("DIQQAT: PUBLIC_URL https:// bilan boshlanmaydi — Click/Payme to'lovidan keyin foydalanuvchi saytga qaytmaydi."
                    + " Railway'da PUBLIC_URL ga saytning to'liq manzilini yozing.");
        }
        if (paynetCredentials && !paynetServiceSet) {
            out.add("DIQQAT: PAYNET_SERVICE_ID berilmagan — Paynet o'chiq turibdi. Paynet bergan xizmat raqamini Railway'ga qo'shing.");
        }
        return out;
    }

    private static String on(boolean b) {
        return b ? "yoqilgan" : "o'chiq";
    }
}
