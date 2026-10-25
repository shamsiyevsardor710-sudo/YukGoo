package uz.yukgo.payment;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uz.yukgo.user.User;

import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** To'lov tizimlari so'rovlarini tekshiradigan sof funksiyalar: imzo, Basic auth, IP ro'yxati, Paynet formatlari. */
class PaymentProtocolTest {
    // Sinov qiymati, haqiqiy kalit emas
    private static final String SECRET = "unit-" + "secret";

    private static Map<String, String> clickPrepare(String amount) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("click_trans_id", "2390012345");
        p.put("service_id", "12345");
        p.put("merchant_trans_id", "501");
        p.put("amount", amount);
        p.put("action", "0");
        p.put("sign_time", "2026-10-25 12:00:00");
        p.put("sign_string", ProviderAuth.md5Hex("2390012345" + "12345" + SECRET + "501" + "50000.00" + "0" + "2026-10-25 12:00:00"));
        return p;
    }

    @Test
    void clickSignatureUsesRawParameters() {
        MockEnvironment env = new MockEnvironment().withProperty("CLICK_SERVICE_ID", "12345")
                .withProperty("CLICK_MERCHANT_ID", "777").withProperty("CLICK_SECRET_KEY", SECRET);
        ClickService click = new ClickService(null, null, new PaymentSettings(env));
        assertTrue(click.signatureOk(clickPrepare("50000.00")));
        // Summa boshqacha yozilsa imzo mos kelmaydi (Click summani qanday yuborsa, o'shanday tekshiriladi)
        assertFalse(click.signatureOk(clickPrepare("50000")));
        Map<String, String> upper = clickPrepare("50000.00");
        upper.put("sign_string", upper.get("sign_string").toUpperCase());
        assertTrue(click.signatureOk(upper));
    }

    @Test
    void md5AndBasicAuth() {
        assertEquals("900150983cd24fb0d6963f7d28e17f72", ProviderAuth.md5Hex("abc"));
        String header = "Basic " + Base64.getEncoder().encodeToString("Paycom:k:ey".getBytes());
        assertArrayEquals(new String[]{"Paycom", "k:ey"}, ProviderAuth.basic(header));
        assertNull(ProviderAuth.basic("Bearer x"));
        assertNull(ProviderAuth.basic("Basic %%%"));
        assertTrue(ProviderAuth.same("a", "a"));
        assertFalse(ProviderAuth.same("a", null));
    }

    @Test
    void ipAllowList() {
        assertTrue(ProviderAuth.ipAllowed("1.2.3.4", List.of()));
        assertTrue(ProviderAuth.ipAllowed("185.234.113.5", List.of("185.234.113.0/28")));
        assertFalse(ProviderAuth.ipAllowed("185.234.113.16", List.of("185.234.113.0/28")));
        assertTrue(ProviderAuth.ipAllowed("::ffff:10.0.0.7", List.of("10.0.0.7")));
        assertFalse(ProviderAuth.ipAllowed(null, List.of("10.0.0.0/8")));
    }

    @Test
    void paynetFormats() {
        User u = new User();
        u.setNames("Akmal", "rahimov");
        assertEquals("Akmal R.", PaynetService.maskName(u));
        assertEquals("2026-10-25 17:00:00", PaynetService.format(Instant.parse("2026-10-25T12:00:00Z")));
    }

    @Test
    void startupWarnings() {
        assertEquals(1, PaymentStartupCheck.warnings(true, false, false, "http://localhost:8080").size());
        assertEquals(1, PaymentStartupCheck.warnings(false, true, false, "https://yukgo.uz").size());
        assertTrue(PaymentStartupCheck.warnings(true, true, true, "https://yukgo.uz").isEmpty());
    }
}
