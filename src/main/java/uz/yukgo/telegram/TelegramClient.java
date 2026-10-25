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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    /** Botning o'z Telegram ID si (getMe) — kanalda admin ekanini tekshirish uchun */
    private volatile long botId;

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

    /** Bot tokeni ko'rinishidagi matn: "123456789:AA…" (kamida 30 belgili kalit qismi bilan) */
    private static final Pattern TOKEN_LIKE = Pattern.compile("\\d{6,}:[A-Za-z0-9_-]{30,}");

    /**
     * Matndan bot tokenini olib tashlaydi. Ulanish xatolarida (masalan, "Read timed out") Spring xato xabariga
     * so'rov manzilini to'liq qo'shadi, manzilda esa token bor. Token o'rniga uchta yulduzcha qoladi.
     */
    public static String redact(String text) {
        return text == null ? null : TOKEN_LIKE.matcher(text).replaceAll("***");
    }

    /** Xatoning logga yozsa bo'ladigan matni — tokensiz. Telegram xatolarini logga faqat shu orqali yozing. */
    public static String safe(Throwable e) {
        if (e == null) return null;
        return redact(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
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
                botId = res.path("result").path("id").asLong(0);
                healthy = true;
                return true;
            }
        } catch (RestClientResponseException e) {
            log.error("Telegram tokenni rad etdi ({}): {}", e.getStatusCode().value(), e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Telegram'ga ulanib bo'lmadi: {}", safe(e));
        }
        healthy = false;
        return false;
    }

    /** Sinxron yuborish (tasdiqlash kodi uchun — natija muhim). */
    public boolean sendNow(long chatId, String html, Object replyMarkup) {
        List<Object> markups = new ArrayList<>();
        if (replyMarkup != null) markups.add(replyMarkup);
        return deliver(chatId, html, markups);
    }

    /**
     * Tugmalarni navbat bilan sinaydi: Telegram birini rad etsa (400, masalan, Mini App tugmasi), keyingisi bilan,
     * oxirida tugmasiz yuboradi — xabarning o'zi baribir yetib borsin.
     */
    private boolean deliver(long chatId, String html, List<Object> markups) {
        if (!enabled()) {
            log.info("[TELEGRAM o'chiq] chat={} xabar: {}", chatId, html);
            return false;
        }
        List<Object> attempts = new ArrayList<>(markups);
        attempts.add(null);
        for (int i = 0; i < attempts.size(); i++) {
            Object markup = attempts.get(i);
            try {
                post(chatId, html, markup);
                return true;
            } catch (RestClientResponseException e) {
                if (markup != null && e.getStatusCode().value() == 400) continue;
                log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getResponseBodyAsString());
                return false;
            } catch (Exception e) {
                log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, safe(e));
                return false;
            }
        }
        return false;
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

    /**
     * Bir nechta tugma varianti bilan sinxron yuborish: Telegram birinchisini rad etsa (400 — masalan, havola juda uzun),
     * keyingisi bilan, oxirida tugmasiz.
     */
    public boolean sendNowWithFallbacks(long chatId, String html, List<Object> markups) {
        List<Object> list = new ArrayList<>();
        for (Object m : markups) if (m != null) list.add(m);
        return deliver(chatId, html, list);
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

    /**
     * Mini App tugmali xabar: bosilganda YukGo Telegram ichida ochiladi (avtomatik kirish bilan).
     * Telegram Mini App tugmasini qabul qilmasa — oddiy havola, u ham bo'lmasa — tugmasiz.
     */
    @Async
    public void sendWithAppButton(long chatId, String html, String buttonText, String appUrl, String webUrl) {
        List<Object> markups = new ArrayList<>();
        Map<String, Object> app = appButton(buttonText, appUrl);
        if (app != null) markups.add(app);
        Map<String, Object> link = linkButton(buttonText, webUrl);
        if (link != null) markups.add(link);
        deliver(chatId, html, markups);
    }

    /** Ommaviy xabar: Telegram limitidan oshmaslik uchun sekin yuboriladi. */
    @Async
    public void broadcast(List<Long> chatIds, String html) {
        int ok = 0;
        for (Long c : chatIds) {
            if (sendNow(c, html, null)) ok++;
            if (!pause(40)) return;
        }
        log.info("Ommaviy xabar {}/{} ta foydalanuvchiga yetkazildi", ok, chatIds.size());
    }

    /**
     * Admin botga yuborgan xabar(lar)ni — rasm, video, dumaloq video, ovozli xabar, fayl, matn yoki albom —
     * barcha foydalanuvchilarga nusxa qilib yuboradi ("Forwarded from" belgisisiz, izohi bilan birga).
     * Sekundiga ~25 ta xabar (Telegram limiti 30). Oxirida adminning so'rov xabari natija bilan yangilanadi.
     */
    @Async
    public void broadcastCopy(List<Long> chatIds, long fromChatId, List<Integer> messageIds,
                              long reportChatId, int reportMessageId) {
        int ok = 0;
        for (Long c : chatIds) {
            if (copy(c, fromChatId, messageIds)) ok++;
            if (!pause(40L * messageIds.size())) return;
        }
        int failed = chatIds.size() - ok;
        log.info("Ommaviy xabar (Telegram orqali) {}/{} ta foydalanuvchiga yetkazildi", ok, chatIds.size());
        editMessage(reportChatId, reportMessageId, "✅ <b>Yuborildi:</b> " + ok + " ta foydalanuvchiga."
                + (failed > 0 ? "\n⚠️ " + failed + " tasiga yetmadi (botni bloklagan yoki Telegramdan o'chgan)." : ""), null);
    }

    /** Bitta xabar — copyMessage, albom — copyMessages (albom ko'rinishi saqlanadi). 429 da bir marta kutib qayta uradi. */
    private boolean copy(long toChatId, long fromChatId, List<Integer> messageIds) {
        if (!enabled()) return false;
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", toChatId);
        body.put("from_chat_id", fromChatId);
        String method;
        if (messageIds.size() == 1) {
            method = "/copyMessage";
            body.put("message_id", messageIds.get(0));
        } else {
            method = "/copyMessages";
            body.put("message_ids", messageIds.stream().sorted().toList());
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                rest.post().uri(method).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
                return true;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt == 0) {
                    if (!pause(retryAfterMs(e.getResponseBodyAsString()))) return false;
                    continue;
                }
                log.debug("Nusxa yuborilmadi (chat={}): {}", toChatId, e.getResponseBodyAsString());
                return false;
            } catch (Exception e) {
                log.debug("Nusxa yuborilmadi (chat={}): {}", toChatId, safe(e));
                return false;
            }
        }
        return false;
    }

    private static final Pattern RETRY_AFTER = Pattern.compile("\"retry_after\"\\s*:\\s*(\\d+)");

    /** Telegram "Too Many Requests" javobidagi kutish vaqti (ko'pi bilan 60 soniya) */
    static long retryAfterMs(String body) {
        Matcher m = RETRY_AFTER.matcher(body == null ? "" : body);
        long sec = m.find() ? Long.parseLong(m.group(1)) : 3;
        return Math.min(60, Math.max(1, sec)) * 1000;
    }

    private static boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Javob xabari (masalan, adminga "hammaga yuborilsinmi?" so'rovi) — yuborilgan xabarning ID sini qaytaradi.
     * @return message_id yoki null (yuborilmadi)
     */
    public Integer sendReply(long chatId, String html, Object replyMarkup, Integer replyToMessageId) {
        if (!enabled()) {
            log.info("[TELEGRAM o'chiq] chat={} xabar: {}", chatId, html);
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        if (replyToMessageId != null) {
            body.put("reply_parameters", Map.of("message_id", replyToMessageId, "allow_sending_without_reply", true));
        }
        try {
            JsonNode res = rest.post().uri("/sendMessage").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().body(JsonNode.class);
            return res != null && res.path("ok").asBoolean() ? res.path("result").path("message_id").asInt() : null;
        } catch (RestClientResponseException e) {
            log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Telegramga yuborib bo'lmadi (chat={}): {}", chatId, safe(e));
        }
        return null;
    }

    /**
     * Kanal yoki guruhga post. Telegram cheklovi (429, guruhga daqiqasiga ~20 ta xabar) bo'lsa — aytilgan vaqt
     * (60 soniyagacha) kutib bir marta qayta uriniladi, post jim tashlab ketilmasin.
     * @return message_id yoki null
     */
    public Integer sendToChat(long chatId, String html, Object replyMarkup) {
        if (!enabled()) {
            log.info("[TELEGRAM o'chiq] chat={} xabar: {}", chatId, html);
            return null;
        }
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                JsonNode res = rest.post().uri("/sendMessage").contentType(MediaType.APPLICATION_JSON).body(body)
                        .retrieve().body(JsonNode.class);
                return res != null && res.path("ok").asBoolean() ? res.path("result").path("message_id").asInt() : null;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt == 0) {
                    if (!pause(retryAfterMs(e.getResponseBodyAsString()))) return null;
                    continue;
                }
                log.warn("Kanalga yuborib bo'lmadi (chat={}): {}", chatId, e.getResponseBodyAsString());
                return null;
            } catch (Exception e) {
                log.warn("Kanalga yuborib bo'lmadi (chat={}): {}", chatId, safe(e));
                return null;
            }
        }
        return null;
    }

    /** Kanal postini tahrirlash, 429 da bir marta kutib qayta uriniladi. "message is not modified" — xato emas. */
    public void editInChat(long chatId, int messageId, String html, Object replyMarkup) {
        if (!enabled()) return;
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                rest.post().uri("/editMessageText").contentType(MediaType.APPLICATION_JSON).body(body).retrieve().toBodilessEntity();
                return;
            } catch (RestClientResponseException e) {
                if (e.getStatusCode().value() == 429 && attempt == 0) {
                    if (!pause(retryAfterMs(e.getResponseBodyAsString()))) return;
                    continue;
                }
                log.debug("Kanal postini yangilab bo'lmadi (chat={}): {}", chatId, e.getResponseBodyAsString());
                return;
            } catch (Exception e) {
                log.debug("Kanal postini yangilab bo'lmadi (chat={}): {}", chatId, safe(e));
                return;
            }
        }
    }

    /** Bot yuborgan xabar matnini almashtiradi; replyMarkup null bo'lsa tugmalar olib tashlanadi. */
    public void editMessage(long chatId, int messageId, String html, Object replyMarkup) {
        if (!enabled()) return;
        Map<String, Object> body = new HashMap<>();
        body.put("chat_id", chatId);
        body.put("message_id", messageId);
        body.put("text", html);
        body.put("parse_mode", "HTML");
        body.put("disable_web_page_preview", true);
        if (replyMarkup != null) body.put("reply_markup", replyMarkup);
        try {
            rest.post().uri("/editMessageText").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.debug("Xabarni yangilab bo'lmadi (chat={}): {}", chatId, safe(e));
        }
    }

    /** Inline tugma bosilganda Telegram "soat" belgisini olib tashlash uchun javob kutadi (text — qisqa bildirishnoma). */
    public void answerCallback(String callbackQueryId, String text) {
        if (!enabled() || callbackQueryId == null) return;
        Map<String, Object> body = new HashMap<>();
        body.put("callback_query_id", callbackQueryId);
        if (text != null) body.put("text", text);
        try {
            rest.post().uri("/answerCallbackQuery").contentType(MediaType.APPLICATION_JSON).body(body)
                    .retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.debug("answerCallbackQuery xatosi: {}", safe(e));
        }
    }

    public long botId() {
        return botId;
    }

    /**
     * Kanal yoki guruh haqida (getChat): id, title, username, type. Topilmasa yoki bot ko'ra olmasa — null.
     * @param chat "@username" yoki "-100..." ko'rinishidagi ID
     */
    public JsonNode getChat(String chat) {
        if (!enabled() || chat == null || chat.isBlank()) return null;
        Object id = chat.trim().matches("-?\\d+") ? (Object) Long.valueOf(chat.trim()) : chat.trim();
        try {
            JsonNode res = rest.post().uri("/getChat").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", id)).retrieve().body(JsonNode.class);
            return res != null && res.path("ok").asBoolean() ? res.path("result") : null;
        } catch (Exception e) {
            log.debug("getChat({}) xatosi: {}", chat, safe(e));
            return null;
        }
    }

    /** Foydalanuvchi (yoki botning o'zi) chatda kim: status ("administrator", "member"...) va huquqlari. Xato bo'lsa null. */
    public JsonNode getChatMember(long chatId, long userId) {
        if (!enabled() || userId == 0) return null;
        try {
            JsonNode res = rest.post().uri("/getChatMember").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("chat_id", chatId, "user_id", userId)).retrieve().body(JsonNode.class);
            return res != null && res.path("ok").asBoolean() ? res.path("result") : null;
        } catch (Exception e) {
            log.debug("getChatMember({}) xatosi: {}", chatId, safe(e));
            return null;
        }
    }

    public JsonNode getUpdates(long offset, int timeoutSec) {
        return rest.get()
                .uri(u -> u.path("/getUpdates")
                        .queryParam("offset", offset)
                        .queryParam("timeout", timeoutSec)
                        // callback_query — admin "Hammaga yuborish" tugmasini bosganda
                        .queryParam("allowed_updates", "[\"message\",\"callback_query\"]")
                        .build())
                .retrieve().body(JsonNode.class);
    }

    public void deleteWebhook() {
        try {
            rest.post().uri("/deleteWebhook").retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("deleteWebhook xatosi: {}", safe(e));
        }
    }

    public void setMyCommands(List<Map<String, String>> commands) {
        try {
            rest.post().uri("/setMyCommands").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("commands", commands)).retrieve().toBodilessEntity();
        } catch (Exception e) {
            log.warn("setMyCommands xatosi: {}", safe(e));
        }
    }

    /**
     * Botdagi menyu tugmasi (yozish maydoni yonida): bosilganda sayt Telegram ichida Mini App bo'lib ochiladi.
     * Telegram faqat https manzilni qabul qiladi.
     * @return tugma o'rnatildimi
     */
    public boolean setMenuButton(String text, String url) {
        if (!isHttpsPublic(url)) return false;
        try {
            rest.post().uri("/setChatMenuButton").contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("menu_button", Map.of("type", "web_app", "text", text, "web_app", Map.of("url", url))))
                    .retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            log.warn("Mini App menyu tugmasini o'rnatib bo'lmadi: {}", e.getResponseBodyAsString());
        } catch (Exception e) {
            log.warn("Mini App menyu tugmasini o'rnatib bo'lmadi: {}", safe(e));
        }
        return false;
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

    /** Mini App tugmasi: sayt Telegram ichida ochiladi. Faqat ochiq https manzil bo'lsa (aks holda null). */
    public static Map<String, Object> appButton(String text, String url) {
        if (!isHttpsPublic(url)) return null;
        return Map.of("inline_keyboard", List.of(List.of(Map.of("text", text, "web_app", Map.of("url", url)))));
    }

    static boolean isHttpsPublic(String url) {
        return url != null && url.startsWith("https://") && isPublicUrl(url);
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
