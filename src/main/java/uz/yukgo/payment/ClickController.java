package uz.yukgo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import uz.yukgo.common.ClientIp;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Click so'rovlari uchun manzillar (Click kabinetida ko'rsatiladi):
 *  Prepare URL:  https://SAYT/api/pay/click/prepare
 *  Complete URL: https://SAYT/api/pay/click/complete
 * Ikkalasi bir xil ishlaydi — qaysi bosqich ekanini "action" parametri aytadi.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class ClickController {
    private final ClickService click;
    private final PaymentSettings settings;

    @PostMapping(value = {"/api/pay/click", "/api/pay/click/prepare", "/api/pay/click/complete"},
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> callback(@RequestParam Map<String, String> params) {
        try {
            String ip = ClientIp.current();
            if (!ProviderAuth.ipAllowed(ip, settings.allowedIps(PaymentProvider.CLICK))) {
                log.warn("Click: ruxsat etilmagan IP manzildan so'rov ({})", ip);
                return ResponseEntity.status(403).body(Map.of("error", -8, "error_note", "Access denied"));
            }
            return ResponseEntity.ok(click.handle(params));
        } catch (RuntimeException e) {
            log.error("Click so'rovini bajarib bo'lmadi (buyurtma={}): {}", params.get("merchant_trans_id"), e.toString());
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("click_trans_id", params.get("click_trans_id"));
            r.put("merchant_trans_id", params.get("merchant_trans_id"));
            r.put("error", -7);
            r.put("error_note", "Failed to update user");
            return ResponseEntity.ok(r);
        }
    }
}
