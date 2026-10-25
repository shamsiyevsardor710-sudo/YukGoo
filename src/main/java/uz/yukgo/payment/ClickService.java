package uz.yukgo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Click SHOP API: Click serverimizga ikki so'rov yuboradi (application/x-www-form-urlencoded):
 *  Prepare  (action=0) — foydalanuvchi to'lovni tasdiqladi, pul hali yechilmagan: buyurtmani tekshirib, urinishni yozamiz;
 *  Complete (action=1) — pul yechildi (error=0) yoki yechilmadi (error&lt;0): balansni to'ldiramiz yoki urinishni bekor qilamiz.
 * Imzo: md5(click_trans_id + service_id + SECRET_KEY + merchant_trans_id [+ merchant_prepare_id] + amount + action + sign_time).
 * Javob xato kodlari Click hujjatidagidek: 0, -1 … -9.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClickService {
    static final List<String> REQUIRED = List.of("click_trans_id", "service_id", "merchant_trans_id", "amount", "action",
            "sign_time", "sign_string");

    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentSettings settings;

    @Transactional
    public Map<String, Object> handle(Map<String, String> p) {
        boolean complete = "1".equals(p.get("action"));
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("click_trans_id", number(p.get("click_trans_id")));
        r.put("merchant_trans_id", p.get("merchant_trans_id"));
        r.put(complete ? "merchant_confirm_id" : "merchant_prepare_id", null);

        for (String k : REQUIRED) {
            if (blank(p.get(k))) return error(r, -8, "Error in request from click");
        }
        if (complete && blank(p.get("merchant_prepare_id"))) return error(r, -8, "Error in request from click");
        if (!settings.clickEnabled() || !settings.clickServiceId().equals(p.get("service_id").trim()) || !signatureOk(p)) {
            return error(r, -1, "SIGN CHECK FAILED!");
        }
        if (!"0".equals(p.get("action")) && !complete) return error(r, -3, "Action not found");

        BigDecimal amount = decimal(p.get("amount"));
        if (amount == null) return error(r, -2, "Incorrect parameter amount");
        Long rootId = id(p.get("merchant_trans_id"));
        Payment root = rootId == null ? null : payments.lockById(rootId)
                .filter(x -> x.getProvider() == PaymentProvider.CLICK && x.getParentId() == null).orElse(null);
        if (root == null) return error(r, -5, "User does not exist");

        return complete ? complete(p, r, root, amount) : prepare(p, r, root, amount);
    }

    private Map<String, Object> prepare(Map<String, String> p, Map<String, Object> r, Payment root, BigDecimal amount) {
        String clickTransId = p.get("click_trans_id").trim();
        Optional<Payment> existing = payments.findByProviderAndProviderTransId(PaymentProvider.CLICK, clickTransId);
        if (existing.isPresent()) {
            // Click Prepare'ni qayta yubordi — o'sha urinish
            Payment e = existing.get();
            if (!e.rootId().equals(root.getId())) return error(r, -8, "Error in request from click");
            r.put("merchant_prepare_id", e.getId());
            return switch (e.getStatus()) {
                case PAID -> error(r, -4, "Already paid");
                case CANCELLED -> error(r, -9, "Transaction cancelled");
                default -> ok(r);
            };
        }
        if (!sameAmount(amount, root.getAmount())) return error(r, -2, "Incorrect parameter amount");
        List<Payment> family = payments.family(root.getId());
        if (family.stream().anyMatch(x -> x.getStatus() == PaymentStatus.PAID)) return error(r, -4, "Already paid");
        if (PaymentService.orderExpired(root)) return error(r, -9, "Transaction cancelled");
        // Hisobini o'chirgan yoki bloklangan foydalanuvchiga to'lov qabul qilinmaydi (Complete'da esa pul yechilgan — rad etilmaydi)
        if (!service.ownerActive(root.getUserId())) return error(r, -5, "User does not exist");

        // Birinchi urinish — asl qatorning o'zida, keyingilari (masalan, boshqa karta bilan) — alohida qatorda
        Payment attempt = PaymentService.unused(root) ? root : service.child(root);
        attempt.setProviderTransId(clickTransId);
        attempt.setProviderRef(cut(p.get("click_paydoc_id"), 64));
        attempt.setStatus(PaymentStatus.PREPARED);
        attempt.setPreparedAt(Instant.now());
        attempt = payments.save(attempt);
        r.put("merchant_prepare_id", attempt.getId());
        log.info("Click Prepare: to'lov #{} (urinish #{}), click_trans_id={}", root.getId(), attempt.getId(), clickTransId);
        return ok(r);
    }

    private Map<String, Object> complete(Map<String, String> p, Map<String, Object> r, Payment root, BigDecimal amount) {
        String clickTransId = p.get("click_trans_id").trim();
        Long prepareId = id(p.get("merchant_prepare_id"));
        Payment a = prepareId == null ? null : payments.findById(prepareId).orElse(null);
        if (a == null || a.getProvider() != PaymentProvider.CLICK || !a.rootId().equals(root.getId())
                || !clickTransId.equals(a.getProviderTransId())) {
            return error(r, -6, "Transaction does not exist");
        }
        r.put("merchant_confirm_id", a.getId());
        if (a.getStatus() == PaymentStatus.PAID) return error(r, -4, "Already paid");
        if (a.getStatus() == PaymentStatus.CANCELLED) return error(r, -9, "Transaction cancelled");

        long clickError = parseLong(p.get("error"), 0);
        if (clickError < 0) {
            // Pul yechilmadi (kartada mablag' yetmadi va h.k.) — urinish bekor
            String note = p.get("error_note");
            service.cancel(a, "Click: " + (blank(note) ? "xato " + clickError : note.trim() + " (" + clickError + ")"));
            log.info("Click Complete: to'lov #{} (urinish #{}) bekor, Click xatosi {}", root.getId(), a.getId(), clickError);
            return error(r, -9, "Transaction cancelled");
        }
        if (!sameAmount(amount, a.getAmount())) return error(r, -2, "Incorrect parameter amount");
        service.markPaid(a, "Click · to'lov #" + root.getId());
        return ok(r);
    }

    boolean signatureOk(Map<String, String> p) {
        String source = p.get("click_trans_id") + p.get("service_id") + settings.clickSecret() + p.get("merchant_trans_id")
                + nz(p.get("merchant_prepare_id")) + p.get("amount") + p.get("action") + p.get("sign_time");
        String expected = ProviderAuth.md5Hex(source);
        String got = p.get("sign_string").trim().toLowerCase(Locale.ROOT);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), got.getBytes(StandardCharsets.US_ASCII));
    }

    private static Map<String, Object> ok(Map<String, Object> r) {
        r.put("error", 0);
        r.put("error_note", "Success");
        return r;
    }

    private static Map<String, Object> error(Map<String, Object> r, int code, String note) {
        r.put("error", code);
        r.put("error_note", note);
        log.info("Click javobi: buyurtma={}, click_trans_id={}, xato {} ({})", r.get("merchant_trans_id"), r.get("click_trans_id"), code, note);
        return r;
    }

    private static boolean sameAmount(BigDecimal got, long expected) {
        return got.compareTo(BigDecimal.valueOf(expected)) == 0;
    }

    private static BigDecimal decimal(String s) {
        if (blank(s)) return null;
        try {
            BigDecimal v = new BigDecimal(s.trim());
            return v.signum() > 0 ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Long id(String s) {
        if (blank(s) || !s.trim().matches("\\d{1,18}")) return null;
        return Long.parseLong(s.trim());
    }

    private static long parseLong(String s, long def) {
        if (blank(s)) return def;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Javobda click_trans_id son bo'lib qaytadi (Click shuni kutadi); son bo'lmasa — o'zi */
    private static Object number(String s) {
        Long v = id(s);
        return v != null ? v : s;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String cut(String s, int max) {
        if (s == null) return null;
        String t = s.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
