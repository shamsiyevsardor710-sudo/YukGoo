package uz.yukgo.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Payme Merchant API (JSON-RPC 2.0). Biz — "kassa": Payme quyidagi metodlarni chaqiradi:
 *  CheckPerformTransaction — buyurtmani to'lash mumkinmi;
 *  CreateTransaction       — tranzaksiya ochildi (holat 1), pul hali yechilmagan;
 *  PerformTransaction      — pul yechildi (holat 2): balans to'ldiriladi;
 *  CancelTransaction       — bekor qilish (holat -1 yoki to'langandan keyin -2: balansdan qaytariladi);
 *  CheckTransaction, GetStatement, ChangePassword.
 * Summalar tiyinda. Bitta buyurtmada bir vaqtda bitta ochiq tranzaksiya bo'ladi; 12 soatda yakunlanmagani bekor qilinadi.
 * Hammasi bitta tranzaksiyada: xato javobi qaytsa ham, undan oldingi o'zgarish (masalan, muddati o'tgan tranzaksiyani
 * bekor qilish) saqlanadi — Payme algoritmi shuni talab qiladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymeService {
    /** Ochiq tranzaksiya shuncha vaqtda yakunlanmasa bekor bo'ladi (Payme: 43 200 000 ms) */
    static final long TIMEOUT_MS = 43_200_000L;
    /** Bekor qilish sababi: muddati o'tdi */
    static final int REASON_TIMEOUT = 4;

    static final int INVALID_AMOUNT = -31001;
    static final int TRANSACTION_NOT_FOUND = -31003;
    static final int CANNOT_CANCEL = -31007;
    static final int CANNOT_PERFORM = -31008;
    static final int ORDER_NOT_FOUND = -31050;
    static final int ORDER_UNAVAILABLE = -31051;
    static final int ORDER_BUSY = -31052;
    static final int INVALID_REQUEST = -32600;
    static final int METHOD_NOT_FOUND = -32601;

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    private final PaymentRepository payments;
    private final PaymentService service;
    private final PaymentSettings settings;
    private final PaymeKeys keys;

    /** Metod natijasi yoki xatosi (xato bo'lsa ham tranzaksiya yakunlanadi — yuqoridagi izohga qarang) */
    public record Reply(JsonNode result, RpcError error) {}

    @Transactional
    public Reply handle(String method, JsonNode params) {
        try {
            JsonNode result = switch (method) {
                case "CheckPerformTransaction" -> checkPerform(params);
                case "CreateTransaction" -> create(params);
                case "PerformTransaction" -> perform(params);
                case "CancelTransaction" -> cancel(params);
                case "CheckTransaction" -> check(params);
                case "GetStatement" -> statement(params);
                case "ChangePassword" -> changePassword(params);
                default -> throw new RpcError(METHOD_NOT_FOUND, null, "method " + method);
            };
            return new Reply(result, null);
        } catch (RpcError e) {
            log.info("Payme {}: xato {} ({})", method, e.code, e.getMessage());
            return new Reply(null, e);
        }
    }

    // =====================================================================
    // Metodlar
    // =====================================================================

    private JsonNode checkPerform(JsonNode params) {
        long amount = amount(params);
        Payment root = payments.findById(orderId(params)).filter(this::isOrder).orElseThrow(this::orderNotFound);
        requirePayable(root);
        checkOrder(root, amount);
        ObjectNode r = JSON.objectNode();
        r.put("allow", true);
        ObjectNode detail = detail(root);
        if (detail != null) r.set("detail", detail);
        return r;
    }

    private JsonNode create(JsonNode params) {
        String id = transactionId(params);
        JsonNode time = params.get("time");
        if (time == null || !time.canConvertToLong()) throw new RpcError(INVALID_REQUEST, null, "time");
        long amount = amount(params);
        Long orderId = orderId(params);
        Payment root = payments.lockById(orderId).filter(this::isOrder).orElseThrow(this::orderNotFound);

        Payment existing = payments.findByProviderAndProviderTransId(PaymentProvider.PAYME, id).orElse(null);
        if (existing != null) {
            if (!existing.rootId().equals(root.getId()) || state(existing) != 1) throw new RpcError(CANNOT_PERFORM);
            if (timedOut(existing)) {
                cancelOpen(existing, REASON_TIMEOUT);
                throw new RpcError(CANNOT_PERFORM, null, "timeout");
            }
            return createResult(existing);
        }

        // Muddati o'tgan ochiq tranzaksiyalar yopiladi (ular buyurtmani band qilmaydi)
        for (Payment p : payments.family(root.getId())) {
            if (state(p) == 1 && timedOut(p)) cancelOpen(p, REASON_TIMEOUT);
        }
        requirePayable(root);
        checkOrder(root, amount);

        Payment t = PaymentService.unused(root) ? root : service.child(root);
        t.setProviderTransId(id);
        t.setProviderTime(time.asLong());
        if (settings.paymeTest()) t.setSandbox(true);
        t.setProviderState(1);
        t.setStatus(PaymentStatus.PREPARED);
        t.setPreparedAt(Instant.now());
        t = payments.save(t);
        log.info("Payme CreateTransaction: to'lov #{} (tranzaksiya #{})", root.getId(), t.getId());
        return createResult(t);
    }

    private JsonNode perform(JsonNode params) {
        Payment t = lockTransaction(transactionId(params));
        int state = state(t);
        if (state == 1) {
            if (timedOut(t)) {
                cancelOpen(t, REASON_TIMEOUT);
                throw new RpcError(CANNOT_PERFORM, null, "timeout");
            }
            // Egasi shu orada hisobini o'chirgan yoki bloklangan — to'lovni o'tkazmaymiz, Payme pulni qaytaradi
            if (!service.ownerActive(t.getUserId())) throw new RpcError(CANNOT_PERFORM, null, "owner not active");
            t.setProviderState(2);
            service.markPaid(t, "Payme · to'lov #" + t.rootId());
        } else if (state != 2) {
            throw new RpcError(CANNOT_PERFORM);
        }
        ObjectNode r = JSON.objectNode();
        r.put("transaction", String.valueOf(t.getId()));
        r.put("perform_time", ms(t.getPaidAt()));
        r.put("state", 2);
        return r;
    }

    private JsonNode cancel(JsonNode params) {
        Payment t = lockTransaction(transactionId(params));
        JsonNode reasonNode = params.get("reason");
        int reason = reasonNode != null && reasonNode.canConvertToInt() ? reasonNode.asInt() : 0;
        int state = state(t);
        if (state == 1) {
            cancelOpen(t, reason);
        } else if (state == 2) {
            // To'langan pul balansdan qaytariladi; ishlatib bo'lingan bo'lsa — bekor qilib bo'lmaydi
            if (!service.reverse(t, "Payme bekor qildi · to'lov #" + t.rootId())) {
                throw new RpcError(CANNOT_CANCEL, null, "balance");
            }
            t.setProviderState(-2);
            t.setCancelReason(reason);
        }
        ObjectNode r = JSON.objectNode();
        r.put("transaction", String.valueOf(t.getId()));
        r.put("cancel_time", ms(t.getCancelledAt()));
        r.put("state", state(t));
        return r;
    }

    private JsonNode check(JsonNode params) {
        Payment t = payments.findByProviderAndProviderTransId(PaymentProvider.PAYME, transactionId(params))
                .orElseThrow(() -> new RpcError(TRANSACTION_NOT_FOUND));
        ObjectNode r = JSON.objectNode();
        r.put("create_time", ms(t.getPreparedAt()));
        r.put("perform_time", ms(t.getPaidAt()));
        r.put("cancel_time", ms(t.getCancelledAt()));
        r.put("transaction", String.valueOf(t.getId()));
        r.put("state", state(t));
        if (t.getCancelReason() != null) r.put("reason", t.getCancelReason());
        else r.putNull("reason");
        return r;
    }

    private JsonNode statement(JsonNode params) {
        JsonNode from = params.get("from"), to = params.get("to");
        if (from == null || to == null || !from.canConvertToLong() || !to.canConvertToLong()) {
            throw new RpcError(INVALID_REQUEST, null, "from/to");
        }
        ArrayNode list = JSON.arrayNode();
        boolean sandbox = settings.paymeTest();
        for (Payment t : payments.statement(PaymentProvider.PAYME, from.asLong(), to.asLong())) {
            // Sinov kassasi tranzaksiyalari haqiqiy hisobotga kirmaydi (va aksincha)
            if (t.isSandboxTransaction() != sandbox) continue;
            ObjectNode o = list.addObject();
            o.put("id", t.getProviderTransId());
            o.put("time", t.getProviderTime() == null ? 0 : t.getProviderTime());
            o.put("amount", t.getAmount() * 100);
            o.putObject("account").put(settings.paymeAccountField(), String.valueOf(t.rootId()));
            o.put("create_time", ms(t.getPreparedAt()));
            o.put("perform_time", ms(t.getPaidAt()));
            o.put("cancel_time", ms(t.getCancelledAt()));
            o.put("transaction", String.valueOf(t.getId()));
            o.put("state", state(t));
            if (t.getCancelReason() != null) o.put("reason", t.getCancelReason());
            else o.putNull("reason");
            o.putNull("receivers");
        }
        ObjectNode r = JSON.objectNode();
        r.set("transactions", list);
        return r;
    }

    private JsonNode changePassword(JsonNode params) {
        JsonNode p = params.get("password");
        if (p == null || !p.isTextual() || p.asText().isBlank()) throw new RpcError(INVALID_REQUEST, null, "password");
        keys.change(p.asText());
        ObjectNode r = JSON.objectNode();
        r.put("success", true);
        return r;
    }

    // =====================================================================
    // Yordamchilar
    // =====================================================================

    /**
     * Buyurtmani shu summaga to'lash mumkinmi. Xatolar: topilmadi (-31050), summa noto'g'ri (-31001),
     * to'langan yoki eskirgan (-31051), boshqa ochiq tranzaksiya bor (-31052).
     */
    private void checkOrder(Payment root, long amountTiyin) {
        if (amountTiyin != root.getAmount() * 100) throw new RpcError(INVALID_AMOUNT, null, "amount");
        List<Payment> family = payments.family(root.getId());
        if (family.stream().anyMatch(p -> p.getStatus() == PaymentStatus.PAID) || PaymentService.orderExpired(root)) {
            throw new RpcError(ORDER_UNAVAILABLE, settings.paymeAccountField(), "paid or expired");
        }
        if (family.stream().anyMatch(p -> state(p) == 1 && !timedOut(p))) {
            throw new RpcError(ORDER_BUSY, settings.paymeAccountField(), "pending");
        }
    }

    /** Tranzaksiyani bloklab olish: avval uning buyurtmasi (hamma o'zgarishlar shu tartibda bloklanadi) */
    private Payment lockTransaction(String id) {
        Long rootId = payments.rootIdOf(PaymentProvider.PAYME, id).orElseThrow(() -> new RpcError(TRANSACTION_NOT_FOUND));
        payments.lockById(rootId);
        return payments.findByProviderAndProviderTransId(PaymentProvider.PAYME, id)
                .orElseThrow(() -> new RpcError(TRANSACTION_NOT_FOUND));
    }

    private void cancelOpen(Payment t, int reason) {
        t.setProviderState(-1);
        t.setCancelReason(reason);
        service.cancel(t, "Payme: bekor qilindi (sabab " + reason + ")");
    }

    private ObjectNode createResult(Payment t) {
        ObjectNode r = JSON.objectNode();
        r.put("create_time", ms(t.getPreparedAt()));
        r.put("transaction", String.valueOf(t.getId()));
        r.put("state", state(t));
        return r;
    }

    /** Fiskal chek ma'lumoti (PAYMENT_MXIK berilgan bo'lsa) */
    private ObjectNode detail(Payment root) {
        if (settings.mxik().isEmpty()) return null;
        ObjectNode d = JSON.objectNode();
        d.put("receipt_type", 0);
        ObjectNode item = d.putArray("items").addObject();
        item.put("title", "YukGo hamyonini to'ldirish");
        item.put("price", root.getAmount() * 100);
        item.put("count", 1);
        item.put("code", settings.mxik());
        if (!settings.packageCode().isEmpty()) item.put("package_code", settings.packageCode());
        item.put("vat_percent", settings.vatPercent());
        return d;
    }

    private boolean isOrder(Payment p) {
        return p.getProvider() == PaymentProvider.PAYME && p.getParentId() == null;
    }

    /**
     * Buyurtmani to'lash mumkinmi: egasi faol foydalanuvchi bo'lsin. Sinov kassasida (PAYME_TEST) — faqat admin ochgan
     * sinov to'lovlari: aks holda Payme'ning ochiq sinov kartalari bilan haqiqiy balans olish mumkin bo'lardi.
     */
    private void requirePayable(Payment root) {
        if (settings.paymeTest() && !root.isTest()) throw new RpcError(ORDER_NOT_FOUND, settings.paymeAccountField(), "sandbox: not a test order");
        if (!service.ownerActive(root.getUserId())) throw new RpcError(ORDER_NOT_FOUND, settings.paymeAccountField(), "owner not active");
    }

    private RpcError orderNotFound() {
        return new RpcError(ORDER_NOT_FOUND, settings.paymeAccountField(), "order not found");
    }

    private Long orderId(JsonNode params) {
        JsonNode account = params.get("account");
        JsonNode v = account == null ? null : account.get(settings.paymeAccountField());
        String s = v == null || v.isNull() || v.isContainerNode() ? "" : v.asText().trim();
        if (!s.matches("\\d{1,18}")) throw orderNotFound();
        return Long.parseLong(s);
    }

    private static String transactionId(JsonNode params) {
        JsonNode id = params.get("id");
        if (id == null || !id.isTextual() || id.asText().isBlank() || id.asText().length() > 64) {
            throw new RpcError(INVALID_REQUEST, null, "id");
        }
        return id.asText();
    }

    /** Summa (tiyin): musbat butun son */
    private static long amount(JsonNode params) {
        JsonNode a = params.get("amount");
        if (a == null || !a.isNumber()) throw new RpcError(INVALID_AMOUNT, null, "amount");
        BigDecimal v = a.decimalValue();
        if (v.signum() <= 0 || v.stripTrailingZeros().scale() > 0) throw new RpcError(INVALID_AMOUNT, null, "amount");
        try {
            return v.longValueExact();
        } catch (ArithmeticException e) {
            throw new RpcError(INVALID_AMOUNT, null, "amount");
        }
    }

    private static int state(Payment p) {
        return p.getProviderState() == null ? 0 : p.getProviderState();
    }

    private static boolean timedOut(Payment p) {
        return p.getPreparedAt() != null && Instant.now().toEpochMilli() - p.getPreparedAt().toEpochMilli() > TIMEOUT_MS;
    }

    private static long ms(Instant t) {
        return t == null ? 0 : t.toEpochMilli();
    }
}
