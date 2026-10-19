package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import uz.yukgo.config.AppProperties;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Telegram Bot API bilan to'g'ridan-to'g'ri HTTP orqali ishlaydi (qo'shimcha kutubxonasiz).
 * Token berilmagan bo'lsa barcha xabarlar faqat logga yoziladi.
 */
@Slf4j
@Component
public class TelegramClient {
    private final AppProperties.Telegram cfg;
    private final RestClient rest;

    public TelegramClient(AppProperties props) {
        this.cfg = props.telegram();
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(10_000);
        f.setReadTimeout(40_000);
        this.rest = RestClient.builder()
                .requestFactory(f)
                .baseUrl("https://api.telegram.org/bot" + (cfg.botToken() == null ? "" : cfg.botToken()))
                .build();
    }

    public boolean enabled() {
        return cfg.enabled();
    }

    public String botUsername() {
        return cfg.botUsername();
    }

    public String deepLink(String startParam) {
        return "https://t.me/" + cfg.botUsername() + (startParam != null ? "?start=" + startParam : "");
    }

    /** Sinxron yuborish (tasdiqlash kodi uchun — natija muhim). */
    public boolean sendNow(long chatId, String html, Object replyMarkup) {
        if (!enabled()) {
            log.info("[TELEGRAM o'chiq] chat={} xabar: {}", chatId, html);
            return false;
        }
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("chat_id", chatId);
            body.put("text", html);
            body.put("parse_mode", "HTML");
            body.put("disable_web_page_preview", true);
            if (replyMarkup != null) body.put("reply_markup", replyMarkup);
            rest.post().uri("/sendMessage").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
            return true;
        } catch (Exception e) {
            log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getMessage());
            return false;
        }
    }

    /** Bildirishnomalar uchun fon rejimida yuborish. */
    @Async
    public void send(long chatId, String html) {
        sendNow(chatId, html, null);
    }

    @Async
    public void sendWithButton(long chatId, String html, String buttonText, String url) {
        sendNow(chatId, html, inlineUrlButton(buttonText, url));
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

    public static Map<String, Object> inlineUrlButton(String text, String url) {
        return Map.of("inline_keyboard", List.of(List.of(Map.of("text", text, "url", url))));
    }

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
