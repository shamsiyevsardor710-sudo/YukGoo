package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import uz.yukgo.config.AppProperties;

import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API bilan to'g'ridan-to'g'ri HTTP orqali ishlaydi (qo'shimcha kutubxonasiz).
 * Token berilmagan yoki noto'g'ri bo'lsa barcha xabarlar faqat logga yoziladi.
 */
@Slf4j
@Component
public class TelegramClient {
    private final String token;
    private final RestClient rest;
    /** Ishga tushishda Telegram'dan olingan haqiqiy username (sozlamadagi qiymatdan ustun) */
    private volatile String username;
    /** Token Telegram tomonidan tasdiqlanganmi (getMe) */
    private volatile boolean healthy = true;

    public TelegramClient(AppProperties props) {
        AppProperties.Telegram cfg = props.telegram();
        this.token = cfg.botToken() == null ? "" : cfg.botToken().trim();
        this.username = normalizeUsername(cfg.botUsername());
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(40_000);
        this.rest = RestClient.builder()
                .requestFactory(f)
                .baseUrl("https://api.telegram.org/bot" + token)
                .build();
    }

    /** "@YukGoobot", "https://t.me/YukGoobot" yoki "YukGoobot" — hammasi "YukGoobot" bo'ladi. */
    static String normalizeUsername(String raw) {
        if (raw == null) return "";
        String s = raw.trim();
        if (s.startsWith("https://t.me/")) s = s.substring("https://t.me/".length());
        while (s.startsWith("@")) s = s.substring(1);
        return s;
    }

    /** Token berilgan va Telegram uni rad etmagan. */
    public boolean enabled() {
        return !token.isEmpty() && healthy;
    }

    /** Ishlash paytida Telegram tokenni rad etsa (401): xabarlar endi faqat logga yoziladi. */
    public void markUnhealthy() {
        healthy = false;
    }

    /** Token sozlamada berilganmi (to'g'riligidan qat'i nazar). */
    public boolean configured() {
        return !token.isEmpty();
    }

    public String botUsername() {
        return username;
    }

    public String deepLink(String startParam) {
        return "https://t.me/" + username + (startParam != null ? "?start=" + startParam : "");
    }

    /**
     * Tokenni tekshiradi (getMe) va botning haqiqiy username'ini oladi.
     * Token noto'g'ri bo'lsa bot o'chiriladi — tasdiqlash kodlari logga yoziladi.
     */
    public boolean verify() {
        try {
            JsonNode res = rest.get().uri("/getMe").retrieve().body(JsonNode.class);
            if (res != null && res.path("ok").asBoolean()) {
                String real = res.path("result").path("username").asText("");
                if (!real.isBlank()) username = real;
                healthy = true;
                return true;
            }
        } catch (RestClientResponseException e) {
            log.error("Telegram tokenni rad etdi ({}): {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Telegram'ga ulanib bo'lmadi: {}", e.getMessage());
        }
        healthy = false;
        return false;
    }

    /** Sinxron yuborish (tasdiqlash kodi uchun — natija muhim). */
    public boolean sendNow(long chatId, String html, Object replyMarkup) {
        if (!enabled()) {
            log.info("[TELEGRAM o'chiq] chat={} xabar: {}", chatId, html);
            return false;
        }
        try {
            post(chatId, html, replyMarkup);
            return true;
        } catch (RestClientResponseException e) {
            // Telegram tugmani rad etsa (masalan, havola noto'g'ri), xabarning o'zi baribir yetib borsin
            if (replyMarkup != null && e.getStatusCode().value() == 400) {
                try {
                    post(chatId, html, null);
                    return true;
                } catch (Exception again) {
                    log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, again.getMessage());
                    return false;
                }
            }
            log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getResponseBodyAsString());
            return false;
        } catch (Exception e) {
            log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getMessage());
            return false;
        }
    }

    private void post(long chatId, String html, Object replyMarkup) {
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        rest.post().uri("/sendMessage").contentType(MediaType.APPLICATION_JSON).body(body)
                .retrieve().toBodilessEntity();
    }

    /** Bildirishnomalar uchun fon rejimida yuborish. */
    @Async
    public void send(long chatId, String html) {
        sendNow(chatId, html, null);
    }

    @Async
    public void sendWithButton(long chatId, String html, String buttonText, String url) {
        sendNow(chatId, html, linkButton(buttonText, url));
    }

    /** Ommaviy xabar: Telegram limitidan oshmaslik uchun sekin yuboriladi. */
    @Async
    public void broadcast(List<Long> chatIds, String html) {
        int ok = 0;
        for (Long c : chatIds) {
            if (sendNow(c, html, null)) ok++;
            try {
                Thread.sleep(40);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        log.info("Ommaviy xabar {}/{} ta foydalanuvchiga yetkazildi", ok, chatIds.size());
    }

    public JsonNode getUpdates(long offset, int timeoutSec) {
        return rest.get()
                .uri(u -> u.path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", timeoutSec)
                        .queryParam("allowed_updates", "[\"message\"]")
                        .build())
                .retrieve().body(JsonNode.class);
    }

    public void deleteWebhook() {
        try {
            rest.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("deleteWebhook xatosi: {}", e.getMessage());
        }
    }

    public void setMyCommands(List<Map<String, String>> commands) {
        try {
            rest.post().uri("/setMyCommands").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("commands", commands)).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("setMyCommands xatosi: {}", e.getMessage());
        }
    }

    // ---- Klaviaturalar ----

    public static Map<String, Object> contactKeyboard() {
        return Map.of(
                "keyboard", List.of(List.of(Map.of("text", "📱 Telefon raqamni yuborish", "request_contact", true))),
                "resize_keyboard", true,
                "one_time_keyboard", true);
    }

    public static Map<String, Object> mainMenu() {
        return Map.of(
                "keyboard", List.of(
                        List.of(Map.of("text", "📦 Buyurtmalarim"), Map.of("text", "🔑 Kod olish")),
                        List.of(Map.of("text", "⭐ Profilim"), Map.of("text", "ℹ️ Yordam"))),
                "resize_keyboard", true);
    }

    /**
     * Havolali tugma. Telegram faqat internetdagi ochiq manzillarni qabul qiladi:
     * localhost kabi manzil bo'lsa tugma qo'shilmaydi (null), xabar tugmasiz boradi.
     */
    public static Map<String, Object> linkButton(String text, String url) {
        if (!isPublicUrl(url)) return null;
        return Map.of("inline_keyboard", List.of(List.of(Map.of("text", text, "url", url))));
    }

    static boolean isPublicUrl(String url) {
        try {
            URI u = URI.create(url);
            String host = u.getHost();
            boolean web = "https".equals(u.getScheme()) || "http".equals(u.getScheme());
            return web && host != null && host.contains(".")
                    && !host.startsWith("127.") && !host.startsWith("10.") && !host.startsWith("192.168.")
                    && !host.equals("0.0.0.0");
        } catch (Exception e) {
            return false;
        }
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
