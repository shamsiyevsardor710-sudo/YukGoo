package uz.yukgo.sms;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uz.yukgo.common.Phones;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Eskiz.uz orqali SMS. Sozlash (Railway → Variables):
 *  • ESKIZ_EMAIL, ESKIZ_PASSWORD — Eskiz kabinetiga kirish ma'lumotlari;
 *  • ESKIZ_FROM — jo'natuvchi nomi (Eskiz bergan; standart "4546").
 * Kirish tokeni ~30 kun amal qiladi: 25 kundan keyin yoki 401 javobida qaytadan olinadi.
 */
@Slf4j
@Component
public class EskizSmsSender implements SmsSender {
    private static final Duration TOKEN_TTL = Duration.ofDays(25);

    private final String email;
    private final String password;
    private final String from;
    private final RestClient rest;

    private volatile String token;
    private volatile Instant tokenAt = Instant.EPOCH;

    public EskizSmsSender(Environment env) {
        this.email = trim(env.getProperty("ESKIZ_EMAIL"));
        this.password = trim(env.getProperty("ESKIZ_PASSWORD"));
        String f = trim(env.getProperty("ESKIZ_FROM"));
        this.from = f.isEmpty() ? "4546" : f;
        String base = trim(env.getProperty("ESKIZ_BASE_URL"));
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(15_000);
        this.rest = RestClient.builder()
                .requestFactory(factory)
                .baseUrl(base.isEmpty() ? "https://notify.eskiz.uz/api" : base)
                .build();
    }

    @Override
    public boolean enabled() {
        return !email.isEmpty() && !password.isEmpty();
    }

    @Override
    public boolean send(String phone, String text) {
        if (!enabled() || phone == null) return false;
        Map<String, Object> body = Map.of(
                "mobile_phone", phone.replace("+", ""),
                "message", text,
                "from", from);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String t = token(attempt > 0);
                JsonNode res = rest.post().uri("/message/sms/send")
                        .header("Authorization", "Bearer " + t)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body(body)
                        .retrieve().body(JsonNode.class);
                // Matnda kod bor — logga yozilmaydi
                log.info("SMS yuborildi: {} (Eskiz id={})", Phones.mask(phone),
                        res == null ? null : res.path("id").asText(""));
                return true;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 401 && attempt == 0) {
                    token = null;   // token eskirgan — bir marta qayta kirib urinamiz
                    continue;
                }
                log.warn("SMS yuborilmadi ({}): {} {}", Phones.mask(phone), e.getStatusCode().value(),
                        cut(e.getResponseBodyAsString()));
                return false;
            } catch (Exception e) {
                log.warn("SMS yuborilmadi ({}): {}", Phones.mask(phone), e.getMessage());
                return false;
            }
        }
        return false;
    }

    /** Kirish tokeni (keshdan yoki Eskiz'ga qayta kirib) */
    private synchronized String token(boolean forceNew) {
        if (!forceNew && token != null && tokenAt.plus(TOKEN_TTL).isAfter(Instant.now())) return token;
        JsonNode res = rest.post().uri("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("email", email, "password", password))
                .retrieve().body(JsonNode.class);
        String t = res == null ? "" : res.path("data").path("token").asText("");
        if (t.isEmpty()) throw new IllegalStateException("Eskiz kirish javobida token yo'q");
        token = t;
        tokenAt = Instant.now();
        return t;
    }

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }

    private static String cut(String s) {
        if (s == null) return "";
        return s.length() > 300 ? s.substring(0, 300) + "…" : s;
    }
}
