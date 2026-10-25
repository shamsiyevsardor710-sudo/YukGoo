package uz.yukgo.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Paynet (JSON-RPC 2.0): biz — "ta'minotchi" (provayder). Foydalanuvchi Paynet ilovasi yoki shoxobchasida YukGo
 * xizmatini tanlab, hisob raqami sifatida YukGo ID sini (yoki telefon raqamini) kiritadi:
 *  GetInformation     — mijozni tekshirish (yashirilgan ism qaytadi);
 *  PerformTransaction — to'lov: balans darhol to'ldiriladi (bir transactionId — bir marta);
 *  CheckTransaction, CancelTransaction (balansda yetarli bo'lsa qaytariladi), GetStatement.
 * Summalar tiyinda, vaqt "yyyy-MM-dd HH:mm:ss" (Toshkent vaqti).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaynetService {
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT);

    static final int INSUFFICIENT_FUNDS = 77;
    static final int TRANSACTION_EXISTS = 201;
    static final int ALREADY_CANCELLED = 202;
    static final int TRANSACTION_NOT_FOUND = 203;
    static final int CLIENT_NOT_FOUND = 302;
    static final int SERVICE_NOT_FOUND = 305;
    static final int BAD_CLIENT_ID = 401;
    static final int MISSING_FIELD = 411;
    static final int BAD_AMOUNT = 413;
    static final int BAD_DATE = 414;
    static final int AMOUNT_TOO_LARGE = 415;
    static final int CLIENT_BLOCKED = 501;
    static final int METHOD_NOT_FOUND = -32601;
    static final int INVALID_PARAMS = -32602;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentSettings settings;
    private final UserRepository users;

    /** Xato bo'lsa RpcError — tranzaksiya bekor bo'ladi (Paynet'da xato javobidan oldin hech narsa yozilmaydi) */
    @Transactional
    public JsonNode handle(String method, JsonNode params) {
        return switch (method) {
            case "GetInformation" -> information(params);
            case "PerformTransaction" -> perform(params);
            case "CheckTransaction" -> check(params);
            case "CancelTransaction" -> cancel(params);
            case "GetStatement" -> statement(params);
            default -> throw new RpcError(METHOD_NOT_FOUND, null, "method " + method);
        };
    }

    // =====================================================================
    // Metodlar
    // =====================================================================

    private JsonNode information(JsonNode params) {
        require(params, "serviceId", "fields");
        requireService(params);
        User u = payer(params);
        ObjectNode r = JSON.objectNode();
        r.put("status", "0");
        r.put("timestamp", format(Instant.now()));
        r.putObject("fields").put("name", maskName(u));
        return r;
    }

    private JsonNode perform(JsonNode params) {
        require(params, "amount", "serviceId", "transactionId", "fields");
        requireService(params);
        String transactionId = transactionId(params);
        long amount = amountSoum(params.get("amount"));
        User u = payer(params);
        if (payments.findByProviderAndProviderTransId(PaymentProvider.PAYNET, transactionId).isPresent()) {
            throw new RpcError(TRANSACTION_EXISTS, null, "duplicate " + transactionId);
        }
        Payment p = new Payment();
        p.setUserId(u.getId());
        p.setProvider(PaymentProvider.PAYNET);
        p.setAmount(amount);
        p.setProviderTransId(transactionId);
        p.setStatus(PaymentStatus.PREPARED);
        p.setPreparedAt(Instant.now());
        // Parallel kelgan bir xil transactionId: ikkinchisi shu yerda unikal cheklovga uriladi va 201 oladi
        p = payments.saveAndFlush(p);
        service.markPaid(p, "Paynet · to'lov #" + p.getId());
        ObjectNode r = JSON.objectNode();
        r.put("timestamp", format(p.getPaidAt()));
        r.put("providerTrnId", p.getId());
        r.putObject("fields").put(settings.paynetClientField(), clientValue(params));
        return r;
    }

    private JsonNode check(JsonNode params) {
        require(params, "serviceId", "transactionId", "timestamp");
        requireService(params);
        String transactionId = transactionId(params);
        timestamp(params.get("timestamp"));
        ObjectNode r = JSON.objectNode();
        Optional<Payment> found = payments.findByProviderAndProviderTransId(PaymentProvider.PAYNET, transactionId);
        if (found.isEmpty()) {
            r.put("transactionState", 3);
            r.put("providerTrnId", 0);
            r.put("timestamp", format(Instant.now()));
            return r;
        }
        Payment p = found.get();
        boolean cancelled = p.getStatus() == PaymentStatus.CANCELLED;
        r.put("transactionState", cancelled ? 2 : p.getStatus() == PaymentStatus.PAID ? 1 : 3);
        r.put("providerTrnId", p.getId());
        Instant at = cancelled ? p.getCancelledAt() : p.getPaidAt();
        r.put("timestamp", format(at != null ? at : p.getCreatedAt()));
        return r;
    }

    private JsonNode cancel(JsonNode params) {
        require(params, "serviceId", "transactionId", "timestamp");
        requireService(params);
        String transactionId = transactionId(params);
        timestamp(params.get("timestamp"));
        Payment p = payments.lockByProviderTrans(PaymentProvider.PAYNET, transactionId)
                .orElseThrow(() -> new RpcError(TRANSACTION_NOT_FOUND));
        if (p.getStatus() == PaymentStatus.CANCELLED) throw new RpcError(ALREADY_CANCELLED);
        // Pul balansdan qaytariladi; foydalanuvchi uni ishlatib bo'lgan bo'lsa — bekor qilib bo'lmaydi
        if (!service.reverse(p, "Paynet bekor qildi · to'lov #" + p.getId())) throw new RpcError(INSUFFICIENT_FUNDS);
        ObjectNode r = JSON.objectNode();
        r.put("providerTrnId", p.getId());
        r.put("timestamp", format(p.getCancelledAt()));
        r.put("transactionState", 2);
        return r;
    }

    private JsonNode statement(JsonNode params) {
        require(params, "serviceId", "dateFrom", "dateTo");
        requireService(params);
        Instant from = timestamp(params.get("dateFrom"));
        Instant to = timestamp(params.get("dateTo"));
        if (from.isAfter(to)) throw new RpcError(BAD_DATE, null, "dateFrom > dateTo");
        ArrayNode list = JSON.arrayNode();
        // Ikkala chegara ham kiradi (soniya aniqligida)
        for (Payment p : payments.paidBetween(PaymentProvider.PAYNET, from, to.plusMillis(999))) {
            ObjectNode o = list.addObject();
            o.put("amount", p.getAmount() * 100);
            o.put("providerTrnId", p.getId());
            JsonNode tid = numberOrText(p.getProviderTransId());
            o.set("transactionId", tid);
            o.put("timestamp", format(p.getPaidAt()));
        }
        ObjectNode r = JSON.objectNode();
        r.set("statements", list);
        return r;
    }

    // =====================================================================
    // Tekshiruvlar
    // =====================================================================

    private static void require(JsonNode params, String... names) {
        for (String n : names) {
            JsonNode v = params.get(n);
            if (v == null || v.isNull()) throw new RpcError(INVALID_PARAMS, null, "missing " + n);
        }
    }

    private void requireService(JsonNode params) {
        JsonNode v = params.get("serviceId");
        String s = v.isNumber() || v.isTextual() ? v.asText().trim() : "";
        if (!s.matches("\\d{1,15}")) throw new RpcError(SERVICE_NOT_FOUND);
        Set<Long> ours = settings.paynetServiceIds();
        if (!ours.contains(Long.parseLong(s))) throw new RpcError(SERVICE_NOT_FOUND, null, "service " + s);
    }

    /** Paynet tranzaksiya raqami: musbat butun son (satr ko'rinishida ham) — bitta ko'rinishga keltiriladi */
    private static String transactionId(JsonNode params) {
        JsonNode v = params.get("transactionId");
        String s = v.isIntegralNumber() || v.isTextual() ? v.asText().trim() : "";
        if (!s.matches("\\d{1,18}") || Long.parseLong(s) <= 0) throw new RpcError(INVALID_PARAMS, null, "transactionId");
        return String.valueOf(Long.parseLong(s));
    }

    /** Summa tiyinda → so'm: butun so'm, chegaralar ichida (413 — noto'g'ri yoki kam, 415 — juda katta) */
    private long amountSoum(JsonNode v) {
        String s = v.isIntegralNumber() || v.isTextual() ? v.asText().trim() : "";
        if (!s.matches("\\d{1,15}")) throw new RpcError(BAD_AMOUNT);
        long tiyin = Long.parseLong(s);
        if (tiyin <= 0 || tiyin % 100 != 0) throw new RpcError(BAD_AMOUNT);
        long soum = tiyin / 100;
        if (soum < settings.minAmount()) throw new RpcError(BAD_AMOUNT, null, "below minimum");
        if (soum > settings.maxAmount()) throw new RpcError(AMOUNT_TOO_LARGE);
        return soum;
    }

    /**
     * To'lovchi: fields[PAYNET_CLIENT_FIELD] — YukGo ID si yoki telefon raqami (9 yoki 998 bilan 12 raqam).
     * Yo'q — 411, noto'g'ri ko'rinish — 401, topilmadi — 302, bloklangan — 501.
     */
    private User payer(JsonNode params) {
        String value = clientValue(params);
        User u;
        if (value.length() == 9 || (value.length() == 12 && value.startsWith("998"))) {
            u = users.findByPhone("+998" + value.substring(value.length() - 9)).orElse(null);
        } else {
            u = users.findById(Long.parseLong(value)).orElse(null);
        }
        if (u == null || u.getStatus() == UserStatus.REMOVED) throw new RpcError(CLIENT_NOT_FOUND);
        if (u.getStatus() == UserStatus.BLOCKED) throw new RpcError(CLIENT_BLOCKED);
        return u;
    }

    private String clientValue(JsonNode params) {
        JsonNode fields = params.get("fields");
        if (fields == null || !fields.isObject()) throw new RpcError(INVALID_PARAMS, null, "fields");
        JsonNode v = fields.get(settings.paynetClientField());
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) throw new RpcError(MISSING_FIELD);
        String s = v.isIntegralNumber() || v.isTextual() ? v.asText().trim() : "";
        if (s.startsWith("+")) s = s.substring(1);
        if (!s.matches("\\d{1,18}")) throw new RpcError(BAD_CLIENT_ID);
        return s;
    }

    private static Instant timestamp(JsonNode v) {
        if (v == null || !v.isTextual()) throw new RpcError(BAD_DATE);
        try {
            return LocalDateTime.parse(v.asText().trim(), TS).atZone(TZ).toInstant();
        } catch (DateTimeParseException e) {
            throw new RpcError(BAD_DATE);
        }
    }

    static String format(Instant t) {
        return TS.format(LocalDateTime.ofInstant(t, TZ));
    }

    /** "Akmal R." — ism va familiyaning bosh harfi */
    static String maskName(User u) {
        String first = u.getFirstName() == null ? "" : u.getFirstName().trim();
        String last = u.getLastName() == null ? "" : u.getLastName().trim();
        if (first.isEmpty()) return last.isEmpty() ? "YukGo" : last;
        if (last.isEmpty()) return first;
        return first + " " + last.substring(0, last.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT) + ".";
    }

    private static JsonNode numberOrText(String s) {
        try {
            return JSON.numberNode(Long.parseLong(s));
        } catch (NumberFormatException e) {
            return JSON.textNode(s);
        }
    }
}
