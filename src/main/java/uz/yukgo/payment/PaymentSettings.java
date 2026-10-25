package uz.yukgo.payment;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * To'lov tizimlari sozlamalari — Railway → Variables (kalitlar kodda va GitHub'da bo'lmasin):
 *  Click:  CLICK_SERVICE_ID, CLICK_MERCHANT_ID, CLICK_SECRET_KEY
 *  Payme:  PAYME_MERCHANT_ID, PAYME_KEY, PAYME_ACCOUNT_FIELD (standart "order_id"). PAYME_TEST=true — sinov kassasi:
 *          Payme foydalanuvchilarga ko'rsatilmaydi, faqat admin «Sinov to'lovi» ishlaydi
 *  Paynet: PAYNET_USERNAME, PAYNET_PASSWORD, PAYNET_SERVICE_ID (Paynet bergan xizmat raqami, bir nechta bo'lsa vergul bilan),
 *          PAYNET_CLIENT_FIELD (standart "client_id"), PAYNET_MERCHANT_ID (ilova havolasi uchun, ixtiyoriy)
 *  Umumiy: PAYMENT_MIN (standart 1 000 so'm), PAYMENT_MAX (standart 10 000 000 so'm)
 *  Fiskal chek (Payme, ixtiyoriy): PAYMENT_MXIK, PAYMENT_PACKAGE_CODE, PAYMENT_VAT_PERCENT
 *  Qo'shimcha himoya (ixtiyoriy): CLICK_ALLOWED_IPS, PAYME_ALLOWED_IPS, PAYNET_ALLOWED_IPS — to'lov tizimi bergan
 *          IP manzillar (vergul bilan, "1.2.3.0/24" ko'rinishida ham bo'ladi). Bo'sh bo'lsa tekshirilmaydi.
 */
@Slf4j
@Component
public class PaymentSettings {
    private final Environment env;

    public PaymentSettings(Environment env) {
        this.env = env;
    }

    String get(String key) {
        String v = env.getProperty(key);
        return v == null ? "" : v.trim();
    }

    long number(String key, long def) {
        String v = get(key);
        if (v.isEmpty()) return def;
        try {
            return Long.parseLong(v.replace(" ", ""));
        } catch (NumberFormatException e) {
            log.warn("{} noto'g'ri qiymat, standart {} ishlatiladi", key, def);
            return def;
        }
    }

    // ---- Click ----
    public boolean clickEnabled() {
        return !get("CLICK_SERVICE_ID").isEmpty() && !get("CLICK_MERCHANT_ID").isEmpty() && !get("CLICK_SECRET_KEY").isEmpty();
    }

    public String clickServiceId() { return get("CLICK_SERVICE_ID"); }
    public String clickMerchantId() { return get("CLICK_MERCHANT_ID"); }
    String clickSecret() { return get("CLICK_SECRET_KEY"); }

    // ---- Payme ----
    public boolean paymeEnabled() {
        return !get("PAYME_MERCHANT_ID").isEmpty() && !get("PAYME_KEY").isEmpty();
    }

    public String paymeMerchantId() { return get("PAYME_MERCHANT_ID"); }
    String paymeKey() { return get("PAYME_KEY"); }
    public boolean paymeTest() { return "true".equalsIgnoreCase(get("PAYME_TEST")); }

    public String paymeAccountField() {
        String f = get("PAYME_ACCOUNT_FIELD");
        return f.isEmpty() ? "order_id" : f;
    }

    // ---- Paynet ----
    /** Login, parol va xizmat raqami — uchalasi ham bo'lsa (xizmat raqamisiz so'rovdagi serviceId'ni tekshirib bo'lmaydi) */
    public boolean paynetEnabled() {
        return paynetCredentials() && !paynetServiceIds().isEmpty();
    }

    public boolean paynetCredentials() {
        return !get("PAYNET_USERNAME").isEmpty() && !get("PAYNET_PASSWORD").isEmpty();
    }

    String paynetUsername() { return get("PAYNET_USERNAME"); }
    String paynetPassword() { return get("PAYNET_PASSWORD"); }
    public String paynetMerchantId() { return get("PAYNET_MERCHANT_ID"); }

    /** Paynet bergan xizmat raqamlari (bo'sh bo'lsa Paynet o'chiq) */
    public Set<Long> paynetServiceIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (String s : list("PAYNET_SERVICE_ID")) {
            try {
                ids.add(Long.parseLong(s));
            } catch (NumberFormatException e) {
                log.warn("PAYNET_SERVICE_ID ichida noto'g'ri qiymat: {}", s);
            }
        }
        return ids;
    }

    /** Paynet so'rovidagi "fields" ichida foydalanuvchi raqami keladigan maydon nomi */
    public String paynetClientField() {
        String f = get("PAYNET_CLIENT_FIELD");
        return f.isEmpty() ? "client_id" : f;
    }

    // ---- Umumiy ----
    public long minAmount() { return Math.max(100, number("PAYMENT_MIN", 1_000)); }
    public long maxAmount() { return Math.max(minAmount(), number("PAYMENT_MAX", 10_000_000)); }

    public String mxik() { return get("PAYMENT_MXIK"); }
    public String packageCode() { return get("PAYMENT_PACKAGE_CODE"); }
    public long vatPercent() { return number("PAYMENT_VAT_PERCENT", 0); }

    public boolean anyEnabled() {
        return clickEnabled() || paymeEnabled() || paynetEnabled();
    }

    /** To'lov tizimi so'rovlari keladigan IP manzillar (bo'sh — cheklov yo'q) */
    List<String> allowedIps(PaymentProvider provider) {
        return list(provider.name() + "_ALLOWED_IPS");
    }

    private List<String> list(String key) {
        String v = get(key);
        if (v.isEmpty()) return List.of();
        return Arrays.stream(v.split("[,;\\s]+")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }
}
