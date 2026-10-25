package uz.yukgo.payment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.ClientIp;

import java.util.Map;

/**
 * Paynet so'rovlari manzili (Paynet'ga beriladi): https://SAYT/api/pay/paynet
 * Tartib: IP ro'yxati (403 / 601) → Basic login-parol (401 / 412) → JSON-RPC → metod. Javoblar HTTP 200.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaynetController {
    static final int ACCESS_DENIED = 601;
    static final int AUTH_ERROR = 412;
    static final int NOT_POST = -32300;
    static final int PARSE_ERROR = -32700;
    static final int INVALID_REQUEST = -32600;
    static final int SYSTEM_ERROR = -32603;

    private static final Map<Integer, String> MESSAGES = Map.ofEntries(
            Map.entry(PaynetService.INSUFFICIENT_FUNDS, "Недостаточно средств на счету клиента для отмены платежа"),
            Map.entry(PaynetService.TRANSACTION_EXISTS, "Транзакция уже существует"),
            Map.entry(PaynetService.ALREADY_CANCELLED, "Транзакция уже отменена"),
            Map.entry(PaynetService.TRANSACTION_NOT_FOUND, "Транзакция не найдена"),
            Map.entry(PaynetService.CLIENT_NOT_FOUND, "Клиент не найден"),
            Map.entry(PaynetService.SERVICE_NOT_FOUND, "Услуга не найдена"),
            Map.entry(PaynetService.BAD_CLIENT_ID, "Неверный формат идентификатора клиента"),
            Map.entry(PaynetService.MISSING_FIELD, "Не задан один или несколько обязательных параметров"),
            Map.entry(PaynetService.BAD_AMOUNT, "Неверная сумма"),
            Map.entry(PaynetService.BAD_DATE, "Неверный формат даты и времени"),
            Map.entry(PaynetService.AMOUNT_TOO_LARGE, "Сумма превышает максимальный лимит"),
            Map.entry(PaynetService.CLIENT_BLOCKED, "Транзакции запрещены для данного плательщика"),
            Map.entry(PaynetService.METHOD_NOT_FOUND, "Запрашиваемый метод не найден"),
            Map.entry(PaynetService.INVALID_PARAMS, "Отсутствуют или неверны обязательные параметры"),
            Map.entry(ACCESS_DENIED, "Доступ запрещен"),
            Map.entry(AUTH_ERROR, "Неверный логин или пароль"),
            Map.entry(NOT_POST, "Метод запроса не POST"),
            Map.entry(PARSE_ERROR, "Ошибка разбора JSON"),
            Map.entry(INVALID_REQUEST, "Неверный JSON-RPC запрос"),
            Map.entry(SYSTEM_ERROR, "Системная ошибка"));

    private final PaynetService paynet;
    private final PaymentSettings settings;
    private final ObjectMapper mapper;

    @PostMapping(value = "/api/pay/paynet", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> rpc(@RequestHeader(value = "Authorization", required = false) String authorization,
                                        @RequestBody(required = false) String body) {
        try {
            return handle(authorization, body);
        } catch (RuntimeException e) {
            log.error("Paynet so'rovini bajarib bo'lmadi: {}", e.toString());
            return ResponseEntity.ok(error(null, SYSTEM_ERROR));
        }
    }

    private ResponseEntity<JsonNode> handle(String authorization, String body) {
        String ip = ClientIp.current();
        if (!ProviderAuth.ipAllowed(ip, settings.allowedIps(PaymentProvider.PAYNET))) {
            log.warn("Paynet: ruxsat etilmagan IP manzildan so'rov ({})", ip);
            return ResponseEntity.status(403).body(error(null, ACCESS_DENIED));
        }
        if (!authorized(authorization)) {
            return ResponseEntity.status(401).header("WWW-Authenticate", "Basic realm=\"paynet\"").body(error(null, AUTH_ERROR));
        }
        JsonNode req;
        try {
            req = body == null || body.isBlank() ? null : mapper.readTree(body);
        } catch (Exception e) {
            return ResponseEntity.ok(error(null, PARSE_ERROR));
        }
        if (req == null) return ResponseEntity.ok(error(null, PARSE_ERROR));
        JsonNode id = req.isObject() ? req.get("id") : null;
        if (id != null && !id.isTextual() && !id.isNumber()) id = null;
        JsonNode method = req.get("method"), params = req.get("params");
        if (!req.isObject() || !"2.0".equals(req.path("jsonrpc").asText()) || id == null || method == null || !method.isTextual()
                || params == null || !params.isObject()) {
            return ResponseEntity.ok(error(id, INVALID_REQUEST));
        }
        try {
            ObjectNode r = JsonNodeFactory.instance.objectNode();
            r.put("jsonrpc", "2.0");
            r.set("id", id);
            r.set("result", paynet.handle(method.asText(), params));
            return ResponseEntity.ok(r);
        } catch (RpcError e) {
            log.info("Paynet {}: xato {} ({})", method.asText(), e.code, e.getMessage());
            return ResponseEntity.ok(error(id, e.code));
        } catch (DataIntegrityViolationException e) {
            // Bir xil transactionId parallel keldi — birinchisi o'tdi, bu takror
            return ResponseEntity.ok(error(id, PaynetService.TRANSACTION_EXISTS));
        } catch (RuntimeException e) {
            log.error("Paynet {} so'rovini bajarib bo'lmadi: {}", method.asText(), e.toString());
            return ResponseEntity.ok(error(id, SYSTEM_ERROR));
        }
    }

    @RequestMapping(value = "/api/pay/paynet", method = {RequestMethod.GET, RequestMethod.PUT, RequestMethod.DELETE, RequestMethod.PATCH},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public JsonNode notPost() {
        return error(null, NOT_POST);
    }

    private boolean authorized(String header) {
        if (!settings.paynetEnabled()) return false;
        String[] cred = ProviderAuth.basic(header);
        if (cred == null) return false;
        boolean user = ProviderAuth.same(cred[0], settings.paynetUsername());
        boolean pass = ProviderAuth.same(cred[1], settings.paynetPassword());
        return user && pass;
    }

    static JsonNode error(JsonNode id, int code) {
        ObjectNode r = JsonNodeFactory.instance.objectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id);
        ObjectNode e = r.putObject("error");
        e.put("code", code);
        e.put("message", MESSAGES.getOrDefault(code, MESSAGES.get(SYSTEM_ERROR)));
        return r;
    }
}
