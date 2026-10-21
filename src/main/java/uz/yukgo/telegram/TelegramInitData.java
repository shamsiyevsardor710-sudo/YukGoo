package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import uz.yukgo.common.ApiException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Telegram Mini App'dan kelgan initData imzosini tekshiradi
 * (https://core.telegram.org/bots/webapps#validating-data-received-via-the-mini-app):
 *  • kalit = HMAC-SHA256("WebAppData" kaliti bilan, bot tokeni);
 *  • tekshiruv satri = "hash" dan boshqa barcha maydonlar, alifbo tartibida, "key=value", "\n" bilan;
 *  • hash = hex(HMAC-SHA256(kalit, tekshiruv satri)).
 * Imzoni faqat bot tokeni egasi (bizning server) yasay oladi, shuning uchun foydalanuvchi ID siga ishonsa bo'ladi.
 */
public final class TelegramInitData {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int MAX_LENGTH = 8192;

    private TelegramInitData() {}

    /** Tekshirilgan Telegram foydalanuvchisi */
    public record Verified(long userId, String firstName, String lastName, String username, Instant authDate) {}

    /**
     * @param maxAge initData shundan eski bo'lsa rad etiladi (o'g'irlangan ma'lumot bilan kirishni cheklaydi)
     * @throws ApiException 400 — imzo noto'g'ri, eskirgan yoki ma'lumot buzilgan (401 emas: frontend 401 da sessiyani o'chiradi)
     */
    public static Verified verify(String initData, String botToken, Duration maxAge, Instant now) {
        Map<String, String> fields = verifySignature(initData, botToken, maxAge, now);
        JsonNode user;
        try {
            user = JSON.readTree(fields.getOrDefault("user", ""));
        } catch (Exception e) {
            throw ApiException.badRequest("Telegram ma'lumotlari buzilgan");
        }
        long id = user == null ? 0 : user.path("id").asLong(0);
        if (id <= 0) throw ApiException.badRequest("Telegram foydalanuvchisi aniqlanmadi");
        return new Verified(id, text(user, "first_name"), text(user, "last_name"), text(user, "username"),
                Instant.ofEpochSecond(Long.parseLong(fields.get("auth_date"))));
    }

    /** Imzo va muddatni tekshiradi; to'g'ri bo'lsa barcha maydonlarni ("hash"siz, URL-dekodlangan) qaytaradi. */
    static Map<String, String> verifySignature(String initData, String botToken, Duration maxAge, Instant now) {
        if (botToken == null || botToken.isBlank()) {
            throw ApiException.badRequest("Telegram bot sozlanmagan");
        }
        if (initData == null || initData.isBlank() || initData.length() > MAX_LENGTH) {
            throw ApiException.badRequest("Telegram ma'lumotlari kelmadi. Ilovani bot orqali qayta oching");
        }
        Map<String, String> fields = new TreeMap<>();
        String hash = null;
        for (String pair : initData.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) continue;
            String key = decode(pair.substring(0, eq));
            String value = decode(pair.substring(eq + 1));
            if ("hash".equals(key)) hash = value;
            else fields.put(key, value);
        }
        if (hash == null || !hash.matches("[0-9a-fA-F]{64}")) {
            throw ApiException.badRequest("Telegram imzosi topilmadi");
        }
        String dataCheck = fields.entrySet().stream()
                .map(e -> e.getKey() + "=" + e.getValue())
                .collect(Collectors.joining("\n"));
        byte[] secret = hmac("WebAppData".getBytes(StandardCharsets.UTF_8), botToken.trim().getBytes(StandardCharsets.UTF_8));
        byte[] expected = hmac(secret, dataCheck.getBytes(StandardCharsets.UTF_8));
        if (!MessageDigest.isEqual(expected, HexFormat.of().parseHex(hash.toLowerCase()))) {
            throw ApiException.badRequest("Telegram imzosi noto'g'ri");
        }

        Instant authDate;
        try {
            authDate = Instant.ofEpochSecond(Long.parseLong(fields.getOrDefault("auth_date", "")));
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("Telegram ma'lumotlari buzilgan");
        }
        if (authDate.isAfter(now.plus(Duration.ofMinutes(5))) || authDate.isBefore(now.minus(maxAge))) {
            throw ApiException.badRequest("Telegram sessiyasi eskirgan. Ilovani yopib, bot orqali qayta oching");
        }
        return fields;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {   // masalan, "%zz" — 500 emas, oddiy rad etish
            throw ApiException.badRequest("Telegram ma'lumotlari buzilgan");
        }
    }

    static byte[] hmac(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA256 mavjud emas", e);
        }
    }
}
