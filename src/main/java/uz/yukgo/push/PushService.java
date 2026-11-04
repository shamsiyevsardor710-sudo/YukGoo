package uz.yukgo.push;

import com.fasterxml.jackson.core.json.JsonReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Android ilovaga push-bildirishnoma (Firebase Cloud Messaging, HTTP v1). Kalit — Railway'dagi FCM_SERVICE_ACCOUNT
 * o'zgaruvchisida (Firebase → Project settings → Service accounts → «Generate new private key» JSON fayli matni) yoki
 * FCM_SERVICE_ACCOUNT_BASE64 da. Berilmasa — push o'chiq, qolgan hammasi avvalgidek ishlaydi.
 * Qo'shimcha kutubxonasiz: kirish tokeni (OAuth) uchun JWT shu yerning o'zida imzolanadi.
 */
@Slf4j
@Service
public class PushService {
    static final String SCOPE = "https://www.googleapis.com/auth/firebase.messaging";
    static final String FCM_URL = "https://fcm.googleapis.com/v1/projects/%s/messages:send";
    /** Ilovada yaratiladigan bildirishnoma kanali (bo'lmasa Android o'zining zaxira kanalidan foydalanadi) */
    static final String CHANNEL = "yukgo";
    /** Bitta foydalanuvchining ko'pi bilan shuncha qurilmasi saqlanadi va xabar oladi (eng oxirgi kirganlari) */
    public static final int MAX_DEVICES = 5;

    record ServiceAccount(String projectId, String clientEmail, PrivateKey key, String tokenUri) {}

    private static final ObjectMapper JSON = JsonMapper.builder().enable(JsonReadFeature.ALLOW_UNESCAPED_CONTROL_CHARS).build();

    private final PushTokenRepository tokens;
    private final ServiceAccount account;
    private final HttpClient http;
    /** Sinov uchun almashtiriladi: FCM manzili */
    String fcmUrlTemplate = FCM_URL;
    private volatile String accessToken;
    private volatile Instant accessTokenExpires = Instant.EPOCH;

    /** Spring shu konstruktorni ishlatadi (ikkinchisi — sinov uchun; @Autowired'siz Spring qaysi birini tanlashni bilmaydi) */
    @Autowired
    public PushService(PushTokenRepository tokens, Environment env) {
        this(tokens, load(env.getProperty("FCM_SERVICE_ACCOUNT"), env.getProperty("FCM_SERVICE_ACCOUNT_BASE64")),
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    PushService(PushTokenRepository tokens, ServiceAccount account, HttpClient http) {
        this.tokens = tokens;
        this.account = account;
        this.http = http;
        if (account != null) log.info("Push-bildirishnomalar yoqilgan (Firebase loyiha: {})", account.projectId());
    }

    public boolean enabled() {
        return account != null;
    }

    static ServiceAccount load(String json, String base64) {
        String text = json != null && !json.isBlank() ? json
                : base64 != null && !base64.isBlank() ? new String(Base64.getMimeDecoder().decode(base64.trim()), StandardCharsets.UTF_8) : null;
        if (text == null) return null;
        try {
            return parse(text);
        } catch (Exception e) {
            // Kalitning o'zi logga yozilmaydi — faqat xato turi
            log.error("FCM_SERVICE_ACCOUNT o'qilmadi ({}): Firebase'dan yuklab olingan JSON faylning to'liq matnini qo'ying. "
                    + "Push-bildirishnomalar o'chiq.", e.getClass().getSimpleName());
            return null;
        }
    }

    static ServiceAccount parse(String text) throws Exception {
        JsonNode n = JSON.readTree(text.trim());
        String pem = n.path("private_key").asText("");
        String b64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "").replaceAll("\\s", "");
        PrivateKey key = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(b64)));
        String project = n.path("project_id").asText("");
        String email = n.path("client_email").asText("");
        if (project.isBlank() || email.isBlank()) throw new IllegalArgumentException("project_id yoki client_email yo'q");
        return new ServiceAccount(project, email, key, n.path("token_uri").asText("https://oauth2.googleapis.com/token"));
    }

    /** Foydalanuvchining barcha qurilmalariga (fon rejimida). link — ilova ichidagi sahifa, masalan "#/orders/5" */
    @Async
    public void send(Long userId, String title, String body, String link) {
        if (account == null || userId == null) return;
        List<PushToken> list;
        try {
            list = tokens.findByUserIdOrderByUpdatedAtDesc(userId);
            if (list.isEmpty()) return;   // ilovasi yo'q (ko'pchilik) — Google'ga murojaat ham yo'q
            accessToken(false);           // kirish tokeni olinmasa — hech bir qurilmaga yuborib bo'lmaydi
        } catch (Exception e) {
            log.warn("Push yuborilmadi (foydalanuvchi {}): {}", userId, safe(e));
            return;
        }
        for (PushToken t : list.stream().limit(MAX_DEVICES).toList()) {
            try {
                sendTo(t, title, body, link);
            } catch (Exception e) {
                log.warn("Push yuborilmadi (foydalanuvchi {}): {}", userId, safe(e));
            }
        }
    }

    /** Logga faqat xato turi (va o'zimizning qisqa izohimiz) — javob matni yoki kalit tushmasin */
    static String safe(Exception e) {
        return e instanceof IllegalStateException ? "IllegalStateException: " + e.getMessage() : e.getClass().getSimpleName();
    }

    void sendTo(PushToken t, String title, String body, String link) throws Exception {
        String payload = JSON.writeValueAsString(message(t.getToken(), title, body, link));
        HttpResponse<String> r = post(payload, false);
        if (r.statusCode() == 401) r = post(payload, true);   // kirish tokeni eskirgan — yangisi bilan bir marta
        if (r.statusCode() >= 300 && dead(r.body())) {
            tokens.delete(t);   // ilova o'chirilgan yoki token eskirgan
        } else if (r.statusCode() >= 300) {
            log.warn("FCM javobi {}: {}", r.statusCode(), r.body() == null ? "" : r.body().replaceAll("\\s+", " ").substring(0, Math.min(200, r.body().length())));
        }
    }

    /**
     * Token endi ishlamaydi: FCM «UNREGISTERED» (ilova o'chirilgan) yoki «token noto'g'ri» deydi. Boshqa xatolarda (masalan,
     * loyiha nomi xato — 404) tokenlar o'chirilmaydi, aks holda sozlamadagi bitta xato hamma qurilmalarni o'chirib yuborardi.
     */
    static boolean dead(String body) {
        return body != null && (body.contains("UNREGISTERED") || body.contains("registration token is not a valid"));
    }

    static Map<String, Object> message(String token, String title, String body, String link) {
        Map<String, Object> android = new LinkedHashMap<>();
        android.put("priority", "HIGH");
        android.put("notification", Map.of("channel_id", CHANNEL, "icon", "ic_stat_yukgo", "sound", "default"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("token", token);
        m.put("notification", Map.of("title", cut(title, 100), "body", cut(body, 240)));
        m.put("data", Map.of("link", link == null ? "" : link));
        m.put("android", android);
        return Map.of("message", m);
    }

    private HttpResponse<String> post(String payload, boolean freshToken) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(String.format(fcmUrlTemplate, account.projectId())))
                .timeout(Duration.ofSeconds(15))
                .header("Authorization", "Bearer " + accessToken(freshToken))
                .header("Content-Type", "application/json; charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)).build();
        return http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    /** OAuth kirish tokeni (taxminan 1 soat amal qiladi; 5 daqiqa qolganda yangilanadi) */
    synchronized String accessToken(boolean force) throws Exception {
        if (!force && accessToken != null && Instant.now().isBefore(accessTokenExpires.minusSeconds(300))) return accessToken;
        String form = "grant_type=" + URLEncoder.encode("urn:ietf:params:oauth:grant-type:jwt-bearer", StandardCharsets.UTF_8)
                + "&assertion=" + URLEncoder.encode(jwt(account, Instant.now()), StandardCharsets.UTF_8);
        HttpRequest req = HttpRequest.newBuilder(URI.create(account.tokenUri())).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (r.statusCode() != 200) throw new IllegalStateException("OAuth " + r.statusCode());
        JsonNode n = JSON.readTree(r.body());
        accessToken = n.path("access_token").asText();
        accessTokenExpires = Instant.now().plusSeconds(n.path("expires_in").asLong(3600));
        return accessToken;
    }

    /** Google OAuth uchun imzolangan JWT (RS256) */
    static String jwt(ServiceAccount a, Instant now) throws Exception {
        Base64.Encoder enc = Base64.getUrlEncoder().withoutPadding();
        String header = enc.encodeToString("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("iss", a.clientEmail());
        claims.put("scope", SCOPE);
        claims.put("aud", a.tokenUri());
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.getEpochSecond() + 3600);
        String payload = enc.encodeToString(JSON.writeValueAsBytes(claims));
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(a.key());
        s.update((header + "." + payload).getBytes(StandardCharsets.US_ASCII));
        return header + "." + payload + "." + enc.encodeToString(s.sign());
    }

    private static String cut(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
