package uz.yukgo.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.ClientIp;

import java.util.Map;

/**
 * Payme so'rovlari manzili (Payme kabinetida "Endpoint URL"): https://SAYT/api/pay/payme
 * Har doim HTTP 200 qaytariladi, xato — JSON-RPC "error" ichida (Payme talabi).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymeController {
    static final int AUTH_ERROR = -32504;
    static final int NOT_POST = -32300;
    static final int PARSE_ERROR = -32700;
    static final int SYSTEM_ERROR = -32400;

    /** Xato matnlari: [o'zbekcha, ruscha, inglizcha] */
    private static final Map<Integer, String[]> MESSAGES = Map.ofEntries(
            Map.entry(PaymeService.INVALID_AMOUNT, new String[]{"Noto'g'ri summa", "Неверная сумма", "Invalid amount"}),
            Map.entry(PaymeService.TRANSACTION_NOT_FOUND, new String[]{"Tranzaksiya topilmadi", "Транзакция не найдена", "Transaction not found"}),
            Map.entry(PaymeService.CANNOT_CANCEL, new String[]{"Tranzaksiyani bekor qilib bo'lmaydi: mablag' ishlatib bo'lingan",
                    "Невозможно отменить транзакцию: средства уже использованы", "Unable to cancel transaction: funds already used"}),
            Map.entry(PaymeService.CANNOT_PERFORM, new String[]{"Amalni bajarib bo'lmaydi", "Невозможно выполнить операцию", "Unable to perform operation"}),
            Map.entry(PaymeService.ORDER_NOT_FOUND, new String[]{"Buyurtma topilmadi", "Заказ не найден", "Order not found"}),
            Map.entry(PaymeService.ORDER_UNAVAILABLE, new String[]{"Buyurtma to'langan yoki muddati o'tgan",
                    "Заказ уже оплачен или просрочен", "Order already paid or expired"}),
            Map.entry(PaymeService.ORDER_BUSY, new String[]{"Buyurtma boshqa tranzaksiya orqali to'lanmoqda",
                    "Заказ ожидает оплаты по другой транзакции", "Order is being paid by another transaction"}),
            Map.entry(PaymeService.INVALID_REQUEST, new String[]{"So'rov noto'g'ri", "Неверный запрос", "Invalid request"}),
            Map.entry(PaymeService.METHOD_NOT_FOUND, new String[]{"Metod topilmadi", "Метод не найден", "Method not found"}),
            Map.entry(AUTH_ERROR, new String[]{"Ruxsat yo'q", "Недостаточно привилегий для выполнения метода", "Insufficient privileges"}),
            Map.entry(NOT_POST, new String[]{"Faqat POST so'rov", "Метод запроса не POST", "Request method is not POST"}),
            Map.entry(PARSE_ERROR, new String[]{"JSON xato", "Ошибка разбора JSON", "JSON parse error"}),
            Map.entry(SYSTEM_ERROR, new String[]{"Tizim xatosi", "Системная ошибка", "System error"}));

    private final PaymeService payme;
    private final PaymentSettings settings;
    private final PaymeKeys keys;
    private final ObjectMapper mapper;

    @PostMapping(value = "/api/pay/payme", produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode rpc(@RequestHeader(value = "Authorization", required = false) String authorization,
                        @RequestBody(required = false) String body) {
        JsonNode req = parse(body);
        JsonNode id = req != null && req.isObject() ? req.get("id") : null;
        try {
            String ip = ClientIp.current();
            if (!ProviderAuth.ipAllowed(ip, settings.allowedIps(PaymentProvider.PAYME))) {
                log.warn("Payme: ruxsat etilmagan IP manzildan so'rov ({})", ip);
                return error(id, AUTH_ERROR, null);
            }
            if (!authorized(authorization)) return error(id, AUTH_ERROR, null);
            if (req == null) return error(null, PARSE_ERROR, null);
            JsonNode method = req.get("method"), params = req.get("params");
            if (!req.isObject() || method == null || !method.isTextual() || params == null || !params.isObject()) {
                return error(id, PaymeService.INVALID_REQUEST, null);
            }
            PaymeService.Reply reply = payme.handle(method.asText(), params);
            if (reply.error() != null) return error(id, reply.error().code, reply.error().data);
            ObjectNode r = JsonNodeFactory.instance.objectNode();
            r.put("jsonrpc", "2.0");
            r.set("id", id);
            r.set("result", reply.result());
            return r;
        } catch (RuntimeException e) {
            // Har qanday kutilmagan xato (masalan, baza) ham Payme kutgan ko'rinishda: HTTP 200 + -32400
            log.error("Payme so'rovini bajarib bo'lmadi ({}): {}", req != null ? req.path("method").asText() : "?", e.toString());
            return error(id, SYSTEM_ERROR, null);
        }
    }

    @RequestMapping(value = "/api/pay/payme", method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode notPost() {
        return error(null, NOT_POST, null);
    }

    /** "Authorization: Basic base64(Paycom:KALIT)" */
    private boolean authorized(String header) {
        if (!settings.paymeEnabled()) return false;
        String[] cred = ProviderAuth.basic(header);
        if (cred == null) return false;
        boolean login = "Paycom".equals(cred[0]);
        return keys.matches(cred[1]) && login;
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) return null;
        try {
            return mapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    static JsonNode error(JsonNode id, int code, String data) {
        String[] m = MESSAGES.getOrDefault(code, MESSAGES.get(SYSTEM_ERROR));
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        ObjectNode msg = e.putObject("message");
        msg.put("uz", m[0]);
        msg.put("ru", m[1]);
        msg.put("en", m[2]);
        if (data != null) e.put("data", data);
        return r;
    }
}
