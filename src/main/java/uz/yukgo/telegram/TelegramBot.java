package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.auth.VerificationRequest;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.channel.OrderCard;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.Phones;
import uz.yukgo.growth.GrowthService;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderKind;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderDtos.CreateOrderRequest;
import uz.yukgo.order.OrderDtos.PlaceInput;
import uz.yukgo.order.OrderService;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.trip.Trip;
import uz.yukgo.trip.TripService;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static uz.yukgo.telegram.TelegramClient.esc;

/**
 * YukGo Telegram boti (long polling).
 *  • /start TOKEN — saytdan kelgan foydalanuvchi: kontakt so'raladi va tasdiqlash kodi yuboriladi
 *  • /start o123_ch3 — kanal postidagi "Taklif berish": buyurtma kartasi va uni ochish tugmasi
 *  • /start new — "Yuk e'lon qilish"; /start src_KOD, /start ref_ID — manba va taklif havolalari
 *  • oddiy matn ("Toshkent - Samarqand, 5 tonna, ertaga, 3 mln") — hisobi ulangan foydalanuvchida e'lon darhol saytga
 *    chiqadi (haydovchining "3 ta joy bor" xabari — reys), ulanmaganida to'ldirilgan forma ochiladi
 *  • kontakt ulashish — raqam botga bog'lanadi
 *  • 📦 Buyurtmalarim, ⭐ Profilim, 🔑 Kod olish, ℹ️ Yordam
 *  • adminlar uchun ommaviy xabar: botga yuborilgan matn, rasm, video, dumaloq video, ovozli xabar, fayl yoki albom
 *    tasdiqlangach barcha foydalanuvchilarga nusxa qilib yuboriladi
 * Barcha bildirishnomalar (yangi taklif, holat o'zgarishi, yozishma) ham shu bot orqali keladi.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TelegramBot {
    private final TelegramClient client;
    private final VerificationService verificationService;
    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final NotificationService notifications;
    private final AdminLogRepository adminLogs;
    private final org.springframework.transaction.support.TransactionTemplate tx;
    private final PlaceIndex places;
    private final GrowthService growth;
    private final OrderService orders;
    private final TripService trips;

    private static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    private static final Pattern ORDER_START = Pattern.compile("o(\\d{1,18})(?:_([a-z0-9_-]{1,40}))?");
    private static final Pattern NEW_START = Pattern.compile("new(?:_([a-z0-9_-]{1,40}))?");
    private static final Pattern SRC_START = Pattern.compile("src_([a-z0-9_-]{1,40})");
    private static final Pattern REF_START = Pattern.compile("ref_(\\d{1,18})");
    /** To'ldirilgan forma havolasi shundan uzun bo'lsa — tavsif qisqartiriladi (Telegram juda uzun tugma havolasini rad etadi) */
    private static final int MAX_BUTTON_URL = 2000;
    private static final String LOAD_HINT = "📦 E'lon bermoqchi bo'lsangiz — qayerdan va qayerga ekanini bitta xabarda yozing, "
            + "e'lon darhol saytga chiqadi. Masalan:\n"
            + "• <i>Toshkent - Samarqand, 5 tonna, ertaga, 3 mln</i>\n"
            + "• <i>Kosonsoydan Toshkentga 2 kishi, ertaga</i>\n"
            + "• <i>Andijon - Toshkent, pochta, bugun</i>\n"
            + "🚗 Haydovchi bo'lsangiz: <i>Kosonsoy - Toshkent, ertaga 07:00, 3 ta joy bor</i>";
    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private volatile boolean running;
    private Thread worker;

    // ---- Ommaviy xabar (adminlar uchun) ----
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Tasdiq kutayotgan so'rov shundan keyin eskiradi */
    private static final Duration DRAFT_TTL = Duration.ofHours(1);
    /** Albomning oxirgi qismi kelgandan keyin shuncha kutib, bitta so'rov chiqariladi */
    private static final long ALBUM_WAIT_MS = 1500;

    /** Admin yuborgan, "Hammaga yuborish" tasdig'ini kutayotgan xabar(lar) */
    record Draft(long chatId, List<Integer> messageIds, String description, Instant createdAt, String text) {}

    /** Albom: Telegram har bir rasm/videoni alohida xabar qilib yuboradi — hammasi kelguncha yig'iladi */
    static final class AlbumBuffer {
        final long chatId;
        final List<Integer> messageIds = new ArrayList<>();
        String caption;
        long lastAt;

        AlbumBuffer(long chatId) {
            this.chatId = chatId;
        }
    }

    private final Map<String, Draft> drafts = new ConcurrentHashMap<>();
    private final Map<String, AlbumBuffer> albums = new ConcurrentHashMap<>();

    /** Aniq bo'lmagan matn (savol yoki faqat ikki joy nomi): «✅ Saytga joylash» bosilishini kutayotgan e'lon */
    record PendingPost(long chatId, String text, Instant createdAt) {}

    private final Map<String, PendingPost> pendingPosts = new ConcurrentHashMap<>();

    /** Guruhdagi e'lon: muallif «✅ YukGo'ga joylash» ni bosib botga o'tganda shu matn joylanadi */
    record GroupPost(long authorId, String text, Instant createdAt) {}

    static final Duration GROUP_POST_TTL = Duration.ofHours(24);
    /** Bitta odamga bitta guruhda — 6 soatda ko'pi bilan bir marta taklif */
    static final Duration GROUP_USER_COOLDOWN = Duration.ofHours(6);
    /** Bitta guruhda — 3 daqiqada ko'pi bilan bitta javob (faol guruh bot xabarlariga to'lib ketmasin) */
    static final Duration GROUP_COOLDOWN = Duration.ofMinutes(3);
    static final int GROUP_MAX_PENDING = 5000;
    private static final Pattern GROUP_START = Pattern.compile("gp_([a-z0-9]{1,16})");

    private final Map<String, GroupPost> groupPosts = new ConcurrentHashMap<>();
    /** "guruh:odam" va "guruh" → oxirgi taklif vaqti */
    private final Map<String, Instant> groupReplies = new ConcurrentHashMap<>();

    /**
     * «Kanallar» bo'limida ulangan (hamkor) guruhlar: bot u yerga e'lon joylaydi, lekin a'zolarning xabarlariga javob
     * yozmaydi — hamkor bunga rozilik bermagan. Konstruktorda emas: sinovlarda kerak emas.
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private uz.yukgo.channel.ChannelFeedRepository feeds;

    record FeedFlag(boolean feed, Instant checkedAt) {}

    private final Map<Long, FeedFlag> feedFlags = new ConcurrentHashMap<>();

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!client.configured()) {
            log.warn("TELEGRAM_BOT_TOKEN berilmagan — bot o'chiq. Tasdiqlash kodlari logga yoziladi.");
            return;
        }
        if (!client.verify()) {
            log.error("Telegram bot ishga tushmadi: TELEGRAM_BOT_TOKEN noto'g'ri yoki internet yo'q. "
                    + "Token ko'rinishi: 1234567890:AAH... — boshida ortiqcha raqam yoki bo'sh joy bo'lmasin. "
                    + "Hozircha tasdiqlash kodlari logga yoziladi.");
            return;
        }
        client.deleteWebhook();
        client.setMyCommands(List.of(
                Map.of("command", "start", "description", "Botni ishga tushirish"),
                Map.of("command", "buyurtmalar", "description", "Faol buyurtmalarim"),
                Map.of("command", "kod", "description", "Tasdiqlash kodini qayta olish"),
                Map.of("command", "yordam", "description", "Yordam")));
        // Yozish maydoni yonidagi tugma: YukGo Telegram ichida (Mini App) ochiladi
        String appUrl = notifications.appUrl("#/");
        if (client.setMenuButton("YukGo", appUrl)) {
            log.info("Mini App menyu tugmasi o'rnatildi: {}", appUrl);
        } else {
            log.warn("Mini App tugmasi o'rnatilmadi: PUBLIC_URL https:// bilan boshlanadigan ochiq manzil bo'lishi kerak (hozir: {})",
                    notifications.siteUrl(""));
        }
        running = true;
        worker = new Thread(this::loop, "telegram-bot");
        worker.setDaemon(true);
        worker.start();
        log.info("Telegram bot @{} ishga tushdi", client.botUsername());
    }

    @PreDestroy
    public void stop() {
        running = false;
        if (worker != null) worker.interrupt();
    }

    private void loop() {
        long offset = 0;
        long lastConflictLog = 0;
        while (running) {
            try {
                // Albom yig'ilayotgan bo'lsa — qisqa so'rov, toki so'rov xabari kechikmasin
                JsonNode res = client.getUpdates(offset, albums.isEmpty() ? 25 : 1);
                if (res == null || !res.path("ok").asBoolean()) {
                    Thread.sleep(3000);
                    continue;
                }
                for (JsonNode upd : res.path("result")) {
                    offset = upd.path("update_id").asLong() + 1;
                    try {
                        if (upd.has("callback_query")) {
                            JsonNode cq = upd.path("callback_query");
                            tx.executeWithoutResult(st -> onCallback(cq));
                        } else {
                            JsonNode m = upd.path("message");
                            tx.executeWithoutResult(st -> handle(m));
                        }
                    } catch (Exception e) {
                        log.error("Bot xabarini qayta ishlashda xato", e);
                        replyFailed(upd);
                    }
                }
                flushAlbums(System.currentTimeMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RestClientResponseException e) {
                int code = e.getStatusCode().value();
                if (code == 401 || code == 404) {
                    // Token @BotFather'da bekor qilingan yoki noto'g'ri — qayta urinishdan foyda yo'q
                    log.error("Telegram tokenni rad etdi ({}): token bekor qilingan yoki noto'g'ri. "
                            + "Yangi tokenni TELEGRAM_BOT_TOKEN ga yozing — ilova qayta ishga tushganda bot ulanadi. "
                            + "Hozircha tasdiqlash kodlari logga yoziladi.", code);
                    client.markUnhealthy();
                    running = false;
                    return;
                }
                if (code == 409) {
                    // Ikkinchi nusxa ham shu token bilan getUpdates qilyapti — logni har 5 soniyada to'ldirmaslik uchun daqiqada bir marta
                    long now = System.currentTimeMillis();
                    if (now - lastConflictLog > 60_000) {
                        lastConflictLog = now;
                        log.warn("Shu token bilan bot boshqa joyda ham ishlayapti (409 Conflict). Bitta token bilan faqat "
                                + "bitta ilova ishlay oladi: kompyuteringizdagi ilovani to'xtating yoki unga alohida test bot bering.");
                    }
                } else {
                    log.warn("Telegram polling xatosi: {}", TelegramClient.safe(e));
                }
                if (!pause(5000)) return;
            } catch (Exception e) {
                log.warn("Telegram polling xatosi: {}", TelegramClient.safe(e));
                if (!pause(5000)) return;
            }
        }
    }

    /** Xabarni qayta ishlab bo'lmadi (masalan, baza vaqtincha ishlamayapti) — foydalanuvchi javobsiz qolmasin */
    private void replyFailed(JsonNode upd) {
        try {
            JsonNode chat = upd.has("callback_query") ? upd.path("callback_query").path("message").path("chat") : upd.path("message").path("chat");
            if (!"private".equals(chat.path("type").asText()) || chat.path("id").asLong(0) == 0) return;
            client.sendNow(chat.path("id").asLong(), "⚠️ Xatolik yuz berdi — birozdan keyin qayta urinib ko'ring.", null);
        } catch (Exception ignored) {
            // javob ham ketmasa — logda bor
        }
    }

    /**
     * Saqlangandan keyin (tranzaksiya tugagach) bajariladi: foydalanuvchiga «✅ chiqdi» faqat haqiqatan saqlanganda boradi va
     * Telegramga yuborish vaqtida e'lon qatori bloklanib turmaydi. Tranzaksiya bo'lmasa — darhol.
     */
    static void afterCommit(Runnable r) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            r.run();
                        }
                    });
        } else {
            r.run();
        }
    }

    /** false qaytarsa — oqim to'xtatilmoqda. */
    private boolean pause(long ms) {
        try {
            Thread.sleep(ms);
            return true;
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    void handle(JsonNode msg) {
        if (msg.isMissingNode() || msg.isNull()) return;
        String chatType = msg.path("chat").path("type").asText();
        if ("group".equals(chatType) || "supergroup".equals(chatType)) {
            onGroupMessage(msg);
            return;
        }
        if (!"private".equals(chatType)) return;
        long chatId = msg.path("chat").path("id").asLong();
        long fromId = msg.path("from").path("id").asLong();
        String username = msg.path("from").path("username").asText(null);

        if (msg.has("contact")) {
            onContact(chatId, fromId, username, msg.path("contact"));
            return;
        }
        String text = msg.path("text").asText("").trim();
        if (text.startsWith("/start")) {
            String param = text.length() > 6 ? text.substring(6).trim() : "";
            onStart(chatId, param);
        } else if (text.equals("/buyurtmalar") || text.equals("📦 Buyurtmalarim")) {
            onMyOrders(chatId);
        } else if (text.equals("/kod") || text.equals("🔑 Kod olish")) {
            onCode(chatId);
        } else if (text.equals("⭐ Profilim")) {
            onProfile(chatId);
        } else if (text.equals("/yordam") || text.equals("ℹ️ Yordam")) {
            onHelp(chatId);
        } else if (broadcastKind(msg) != null && staffByChat(chatId) != null) {
            onBroadcastDraft(chatId, msg);
        } else if (!text.isEmpty() && !text.startsWith("/")) {
            onFreeText(chatId, text);
        } else if (broadcastKind(msg) != null) {
            onMediaFromNonStaff(chatId);
        } else {
            client.sendNow(chatId, "Quyidagi tugmalardan foydalaning 👇", TelegramClient.mainMenu());
        }
    }

    // =====================================================================
    // Guruhlar: bot guruhdagi aniq e'lonni ko'rsa — «✅ YukGo'ga joylash» tugmasi. Bosgan muallif botga o'tadi va e'lon
    // bir bosishda saytga chiqadi (hisobi ulanmagan bo'lsa — to'ldirilgan forma). Guruhda bot hamma xabarni ko'rishi
    // uchun @BotFather → /setprivacy → Disable (yoki botni guruh admini qilish) kerak.
    // =====================================================================

    void onGroupMessage(JsonNode msg) {
        JsonNode from = msg.path("from");
        // Bot, kanal yoki anonim admin nomidan, boshqa joydan uzatilgan xabarlarga javob berilmaydi
        if (from.isMissingNode() || from.path("is_bot").asBoolean(false) || msg.has("sender_chat")
                || msg.has("forward_origin") || msg.has("forward_from") || msg.has("forward_from_chat") || msg.has("via_bot")) return;
        String text = (msg.hasNonNull("text") ? msg.path("text").asText("") : msg.path("caption").asText("")).trim();
        if (text.length() < 12 || text.length() > 1500 || text.startsWith("/")) return;
        long groupId = msg.path("chat").path("id").asLong();
        long authorId = from.path("id").asLong();
        if (groupId == 0 || authorId <= 0) return;
        Instant now = Instant.now();
        String userKey = groupId + ":" + authorId, groupKey = String.valueOf(groupId);
        if (recently(userKey, GROUP_USER_COOLDOWN, now) || recently(groupKey, GROUP_COOLDOWN, now)) return;
        LoadTextParser.Parsed p = parse(text);
        if (p == null || p.question() || !p.hasDetails()) return;   // faqat aniq e'lon: savol va gap-so'zlarga emas
        if (isFeedChat(groupId)) return;
        if (groupPosts.size() >= GROUP_MAX_PENDING) {
            groupPosts.values().removeIf(g -> g.createdAt().isBefore(now.minus(GROUP_POST_TTL)));
            if (groupPosts.size() >= GROUP_MAX_PENDING) return;
        }
        if (groupReplies.size() > 20_000) groupReplies.values().removeIf(t -> t.isBefore(now.minus(GROUP_USER_COOLDOWN)));
        String token = Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
        groupPosts.put(token, new GroupPost(authorId, text, now));
        groupReplies.put(userKey, now);
        groupReplies.put(groupKey, now);
        String html = p.driverOffer()
                ? "🚗 Reysingizni <b>YukGo</b>'ga ham joylaymi? Shu yo'nalishdagi yo'lovchi va yuk egalari ko'radi, yangi e'lonlar sizga keladi."
                : "📲 Shu e'lonni <b>YukGo</b>'ga ham joylaymi? Yo'nalishdagi haydovchilarga xabar boradi, raqamingiz haydovchi "
                + "«Olaman» deguncha yopiq turadi.";
        Map<String, Object> markup = Map.of("inline_keyboard",
                List.of(List.of(Map.of("text", "✅ YukGo'ga joylash", "url", client.deepLink("gp_" + token)))));
        client.sendReply(groupId, html, markup, msg.path("message_id").asInt());
    }

    /** Hamkor (kanallar ro'yxatidagi) guruhmi — 10 daqiqa eslab qolinadi; bazaga ulanib bo'lmasa — javob yozmaymiz */
    boolean isFeedChat(long chatId) {
        if (feeds == null) return false;
        Instant now = Instant.now();
        FeedFlag f = feedFlags.get(chatId);
        if (f == null || f.checkedAt().isBefore(now.minus(Duration.ofMinutes(10)))) {
            boolean feed;
            try {
                feed = feeds.findByChatId(chatId).isPresent();
            } catch (Exception e) {
                feed = true;
            }
            if (feedFlags.size() > 5000) feedFlags.clear();
            f = new FeedFlag(feed, now);
            feedFlags.put(chatId, f);
        }
        return f.feed();
    }

    private boolean recently(String key, Duration window, Instant now) {
        Instant last = groupReplies.get(key);
        return last != null && last.isAfter(now.minus(window));
    }

    /** Guruhdagi «✅ YukGo'ga joylash» → /start gp_TOKEN: faqat e'lon muallifi, bir marta */
    void onGroupStart(long chatId, String token) {
        GroupPost gp = groupPosts.get(token);
        if (gp == null || gp.createdAt().isBefore(Instant.now().minus(GROUP_POST_TTL))) {
            if (gp != null) groupPosts.remove(token, gp);
            client.sendNow(chatId, "⌛ Bu havola eskirgan.\n\n" + LOAD_HINT, TelegramClient.mainMenu());
            return;
        }
        if (gp.authorId() != chatId) {
            client.sendNow(chatId, "Bu tugma e'lon muallifi uchun.\n\n" + LOAD_HINT, TelegramClient.mainMenu());
            return;
        }
        if (!groupPosts.remove(token, gp)) return;   // ikki marta bosildi — bir marta joylanadi
        boolean linked = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).isPresent();
        if (!linked) growth.rememberStart(chatId, "guruh", null);   // qayerdan kelgani (marketing hisobotida)
        onFreeText(chatId, gp.text(), true);
        if (!linked) {
            client.sendNow(chatId, "📱 Telefon raqamingizni yuborsangiz, hisobingiz ulanadi: keyingi e'lonlar shu yerdan bir bosishda "
                    + "chiqadi va haydovchilar takliflari shu yerga keladi 👇", TelegramClient.contactKeyboard());
        }
    }

    // =====================================================================
    // Matn bilan e'lon: "Toshkent - Samarqand, 5 tonna, ertaga, 3 mln" → to'ldirilgan forma
    // =====================================================================

    /** Matndan e'lon (yo'nalish topilmasa yoki tushunib bo'lmasa — null) */
    private LoadTextParser.Parsed parse(String text) {
        try {
            LoadTextParser.Parsed p = places.parser().parse(text, LocalDate.now(TZ));
            return p != null && p.isLoad() ? p : null;
        } catch (Exception e) {
            log.warn("E'lon matnini tushunib bo'lmadi: {}", TelegramClient.safe(e));
            return null;
        }
    }

    void onFreeText(long chatId, String text) {
        onFreeText(chatId, text, false);
    }

    /**
     * @param confirmed foydalanuvchi «✅ Saytga joylash» ni (admin — «E'lon sifatida joylash» ni) bosgan: qayta so'ralmaydi
     */
    void onFreeText(long chatId, String text, boolean confirmed) {
        LoadTextParser.Parsed p = parse(text);
        if (p == null) {
            client.sendNow(chatId, "Quyidagi tugmalardan foydalaning 👇\n\n" + LOAD_HINT, TelegramClient.mainMenu());
            return;
        }
        // Hisobi ulangan, shartlarni qabul qilgan faol foydalanuvchi — e'lon forma to'ldirilmasdan darhol saytga chiqadi.
        // Matn aniq e'lon bo'lmasa (savol yoki faqat ikki joy nomi) — bir bosishli «✅ Saytga joylash» so'raladi.
        User u = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).orElse(null);
        boolean ready = u != null && u.isActive() && u.hasAcceptedTerms() && u.getPhone() != null;
        boolean sure = confirmed || (!p.question() && p.hasDetails());
        String problem = null;
        if (p.driverOffer()) {
            // "Kosonsoy - Toshkent, yo'lovchi va pochta olaman" — bu haydovchining e'loni: reys
            if (ready && u.isDriver()) {
                if (!sure) {
                    askToPost(chatId, text, tripHead(p), "#" + tripPrefillRoute(p), "✏️ Reys formasi");
                    return;
                }
                problem = autoTrip(chatId, u, p);
                if (problem == null) return;
            }
            String route = tripPrefillRoute(p);
            List<Object> buttons = new ArrayList<>();
            buttons.add(TelegramClient.appButton("🚗 Reys e'lon qilish", notifications.appUrl(route)));
            buttons.add(TelegramClient.linkButton("🚗 Reys e'lon qilish", notifications.siteUrl("#" + route)));
            client.sendNowWithFallbacks(chatId, (problem != null ? "⚠️ " + esc(problem) + "\n\n" : "") + tripSummary(p)
                    + (ready && !u.isDriver() ? "\n\n🚗 Avval profilingizga mashinangizni qo'shing — keyingi safar reys shu yerdan "
                    + "darhol chiqadi." : "") + notLinkedNote(u, ready), buttons);
            return;
        }
        if (ready) {
            if (!sure) {
                String brief = prefillRoute(p, p.title().length() > 60 ? p.title().substring(0, 60) : p.title(), null);
                askToPost(chatId, text, loadHead(p), "#" + brief, "✏️ Formani ochish");
                return;
            }
            problem = autoOrder(chatId, u, p);
            if (problem == null) return;
        }
        // Uzun matn: tavsif tugma havolasiga sig'guncha qisqartiriladi; Telegram baribir rad etsa — qisqa variant
        String desc = p.description() != null && !p.description().equals(p.title()) ? p.description() : null;
        String route = prefillRoute(p, p.title(), desc);
        while (desc != null && notifications.appUrl(route).length() > MAX_BUTTON_URL) {
            desc = desc.length() > 60 ? desc.substring(0, desc.length() * 2 / 3) + "…" : null;
            route = prefillRoute(p, p.title(), desc);
        }
        String brief = prefillRoute(p, p.title().length() > 60 ? p.title().substring(0, 60) : p.title(), null);
        List<Object> buttons = new ArrayList<>();
        buttons.add(TelegramClient.appButton("✅ Formani ochish", notifications.appUrl(route)));
        buttons.add(TelegramClient.appButton("✅ Formani ochish", notifications.appUrl(brief)));
        buttons.add(TelegramClient.linkButton("✅ Formani ochish", notifications.siteUrl("#" + brief)));
        client.sendNowWithFallbacks(chatId, (problem != null ? "⚠️ " + esc(problem) + "\n\n" : "") + loadSummary(p)
                + notLinkedNote(u, ready), buttons);
    }

    /** Ro'yxatdan o'tmagan (yoki botga ulanmagan) foydalanuvchiga: ulansa, keyingi e'lon shu yerdan darhol chiqadi */
    private static String notLinkedNote(User u, boolean ready) {
        if (ready) return "";
        if (u == null) return "\n\n💡 Ro'yxatdan o'tganingizdan keyin botga yozgan e'loningiz forma to'ldirmasdan darhol saytga chiqadi.";
        return "";
    }

    /** Aniq bo'lmagan matn: tushunilgani + «✅ Saytga joylash» (bir bosishda chiqadi) va o'zgartirish uchun forma */
    void askToPost(long chatId, String text, String head, String formHash, String formText) {
        Instant expiredBefore = Instant.now().minus(DRAFT_TTL);
        pendingPosts.values().removeIf(x -> x.createdAt().isBefore(expiredBefore));
        String id = Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
        pendingPosts.put(id, new PendingPost(chatId, text, Instant.now()));
        Map<String, Object> post = Map.of("text", "✅ Saytga joylash", "callback_data", "op:" + id);
        String app = notifications.appUrl(formHash);
        String site = notifications.siteUrl(formHash);
        List<Object> variants = new ArrayList<>();
        if (TelegramClient.isHttpsPublic(app)) {
            variants.add(Map.of("inline_keyboard", List.of(List.of(post), List.of(Map.of("text", formText, "web_app", Map.of("url", app))))));
        }
        if (TelegramClient.isPublicUrl(site)) {
            variants.add(Map.of("inline_keyboard", List.of(List.of(post), List.of(Map.of("text", formText, "url", site)))));
        }
        variants.add(Map.of("inline_keyboard", List.of(List.of(post))));
        client.sendNowWithFallbacks(chatId, head + "\n\n❓ Shuni saytga joylaymi? «✅ Saytga joylash» ni bosing — darhol chiqadi "
                + "va shu yo'nalishdagilarga xabar boradi. O'zgartirmoqchi bo'lsangiz — formani oching.", variants);
    }

    /** «✅ Saytga joylash» (op:ID) */
    void onConfirmPost(String callbackId, long chatId, int promptId, String id) {
        PendingPost pp = pendingPosts.get(id);
        boolean expired = pp != null && pp.createdAt().isBefore(Instant.now().minus(DRAFT_TTL));
        // remove(id, pp) — ikki marta bosilsa ham bir marta joylanadi
        if (pp == null || expired || pp.chatId() != chatId || !pendingPosts.remove(id, pp)) {
            if (expired) pendingPosts.remove(id);
            client.answerCallback(callbackId, "Bu so'rov bajarilgan yoki eskirgan — kerak bo'lsa, e'lonni qaytadan yozing");
            return;
        }
        client.answerCallback(callbackId, "Joylanmoqda");
        client.editMessage(chatId, promptId, "⏳ E'lon joylanmoqda…", null);
        onFreeText(chatId, pp.text(), true);
    }

    /**
     * Ulangan foydalanuvchi: e'lon darhol saytga chiqadi va javobda «Ko'rish», «Ulashish», «Bekor qilish» tugmalari.
     * @return null — joylandi (yoki xuddi shunday e'lon bor), aks holda nima xalaqit bergani (forma ochiladi)
     */
    String autoOrder(long chatId, User u, LoadTextParser.Parsed p) {
        OrderService.Created c;
        try {
            c = orders.createFromBot(u, orderRequest(p));
        } catch (ApiException e) {
            return e.getMessage();
        }
        Order o = c.order();
        StringBuilder sb = new StringBuilder(c.duplicate()
                ? "ℹ️ <b>Bu e'lon allaqachon saytda</b> (#" + o.getId() + ") — qayta joylanmadi.\n\n"
                : "✅ <b>E'loningiz saytga chiqdi!</b>\n\n");
        sb.append(OrderCard.text(o)).append("\n\n");
        if (!c.duplicate()) {
            sb.append(c.notified() > 0
                    ? "🔔 Shu yo'nalishdagi <b>" + c.notified() + "</b> ta haydovchiga xabar yuborildi.\n"
                    : "🔔 E'lon bozorda turadi — shu yo'nalishdagi haydovchilar ko'radi.\n");
        }
        sb.append("📩 Haydovchi «Olaman» desa yoki narx taklif qilsa — shu yerga xabar keladi; savollari saytdagi «💬 Xabarlar»da turadi.\n");
        sb.append("🔒 Telefon raqamingiz haydovchi e'loningizni olgandan keyingina ko'rinadi.");
        String html = sb.toString();
        List<Object> buttons = postedButtons("#/orders/" + o.getId(), "📤 Ulashish", shareUrl(o), "❌ Bekor qilish", "oc:" + o.getId());
        afterCommit(() -> client.sendNowWithFallbacks(chatId, html, buttons));
        return null;
    }

    /** Haydovchi: reys darhol saytga chiqadi. @return null — chiqdi, aks holda nima xalaqit bergani */
    String autoTrip(long chatId, User u, LoadTextParser.Parsed p) {
        TripService.BotTrip r;
        try {
            r = trips.createAs(u, tripRequest(p, LocalDateTime.now(TZ)));
        } catch (ApiException e) {
            return e.getMessage();
        }
        Trip t = r.trip();
        StringBuilder sb = new StringBuilder(r.duplicate()
                ? "ℹ️ <b>Bu reys allaqachon saytda</b> — qayta joylanmadi.\n\n"
                : "✅ <b>Reysingiz saytga chiqdi!</b>\n\n");
        sb.append("📍 ").append(esc(OrderCard.place(t.getFromRegion(), t.getFromDistrict()))).append(" → ")
                .append(esc(OrderCard.place(t.getToRegion(), t.getToDistrict()))).append('\n');
        sb.append("🕒 ").append(tripWhen(t)).append('\n');
        if (t.getSeats() != null) sb.append("💺 ").append(t.getSeats()).append(" ta bo'sh o'rin\n");
        sb.append('\n');
        if (!r.duplicate()) {
            sb.append(r.matches() > 0
                    ? "🔔 Shu yo'nalishdagi <b>" + r.matches() + "</b> ta ochiq e'lon egasiga «haydovchi bor» xabari bordi.\n"
                    : "🔔 Shu yo'nalishda yangi yo'lovchi, pochta yoki yuk chiqsa — shu yerga xabar keladi.\n");
        }
        sb.append(p.departTime() == null && !t.isDaily()
                ? "🕒 Vaqtni aniq yozmadingiz — kerak bo'lsa, «Ko'rish» orqali o'zgartiring."
                : "✏️ O'zgartirish kerak bo'lsa — «Ko'rish» ni bosing.");
        String html = sb.toString();
        List<Object> buttons = postedButtons("#/trips?mine=1", "📤 Ulashish", tripShareUrl(t), "❌ O'chirish", "tc:" + t.getId());
        afterCommit(() -> client.sendNowWithFallbacks(chatId, html, buttons));
        return null;
    }

    /** «Bugun, 15:00», «Ertaga, 07:00», «Har kuni, 07:00» */
    static String tripWhen(Trip t) {
        String time = t.getDepartAt().toLocalTime().format(HM);
        return t.isDaily() ? "Har kuni, " + time : OrderCard.dateLabel(t.getDepartAt().toLocalDate()) + ", " + time;
    }

    /** Botdan e'lon: forma o'rniga to'g'ridan-to'g'ri (cheklovlar — formadagidek) */
    static CreateOrderRequest orderRequest(LoadTextParser.Parsed p) {
        OrderKind kind = p.kind() == null ? OrderKind.CARGO : p.kind();
        String desc = p.description() != null && !p.description().equals(p.title()) ? p.description() : null;
        Double weight = kind != OrderKind.PASSENGER && p.weightKg() != null && p.weightKg() >= 0.1 ? p.weightKg() : null;
        Integer seats = kind == OrderKind.PASSENGER && p.seats() != null && p.seats() >= 1 && p.seats() <= 30 ? p.seats() : null;
        return new CreateOrderRequest(p.title(), kind == OrderKind.CARGO ? p.cargoType() : null, weight, null, desc,
                kind == OrderKind.CARGO ? p.bodyType() : null,
                new PlaceInput(p.from().regionId(), p.from().districtId(), null, null, null),
                new PlaceInput(p.to().regionId(), p.to().districtId(), null, null, null),
                null, null, p.pickupDate(), p.price(), null, null, false, null, null, kind, seats);
    }

    /**
     * Haydovchi reysi: sana va soat matndan. Soat yozilmasa — bugun bo'lsa bir soatdan keyin (yarim soatga yaxlitlab),
     * boshqa kun yoki «har kuni» bo'lsa — 08:00. Bugungi soat o'tib ketgan bo'lsa — ertaga shu soatda.
     */
    static TripService.TripRequest tripRequest(LoadTextParser.Parsed p, LocalDateTime now) {
        LocalDate today = now.toLocalDate();
        LocalDate date = p.pickupDate() == null || p.daily() ? today : p.pickupDate();
        LocalDateTime at;
        if (p.departTime() != null) {
            at = date.atTime(p.departTime());
            if (!p.daily() && p.pickupDate() == null && at.isBefore(now.minusHours(1))) at = at.plusDays(1);
        } else if (date.equals(today) && !p.daily()) {
            LocalDateTime soon = now.plusHours(1).withSecond(0).withNano(0);
            at = soon.withMinute(soon.getMinute() < 30 ? 30 : 0).plusHours(soon.getMinute() < 30 ? 0 : 1);
        } else {
            at = date.atTime(8, 0);
        }
        // Haydovchi ko'p qatorli xabar yozgan bo'lsa — to'liq matni izohga (telefonsiz, 500 belgigacha)
        String comment = p.description() != null && !p.description().equals(p.title()) ? p.description() : null;
        if (comment != null && comment.length() > 500) comment = comment.substring(0, 499) + "…";
        return new TripService.TripRequest(p.from().regionId(), p.from().districtId(), p.to().regionId(), p.to().districtId(),
                at, null, null, p.price() != null ? OrderCard.money(p.price()) : null, comment, p.daily(),
                p.seats() != null && p.seats() >= 1 && p.seats() <= 60 ? p.seats() : null);
    }

    /** Joylangan e'lon/reys tugmalari: «👀 Ko'rish» (ilova), ixtiyoriy havola tugmasi va «❌ ...» (callback) */
    private List<Object> postedButtons(String hashLink, String linkText, String linkUrl, String cancelText, String cancelData) {
        Map<String, Object> cancel = Map.of("text", cancelText, "callback_data", cancelData);
        Map<String, Object> extra = linkUrl != null && TelegramClient.isPublicUrl(linkUrl) ? Map.of("text", linkText, "url", linkUrl) : null;
        List<Object> variants = new ArrayList<>();
        String app = notifications.appUrl(hashLink);
        String site = notifications.siteUrl(hashLink);
        if (TelegramClient.isHttpsPublic(app)) variants.add(keyboard(Map.of("text", "👀 Ko'rish", "web_app", Map.of("url", app)), extra, cancel));
        if (TelegramClient.isPublicUrl(site)) variants.add(keyboard(Map.of("text", "👀 Ko'rish", "url", site), extra, cancel));
        variants.add(keyboard(null, null, cancel));
        return variants;
    }

    private static Map<String, Object> keyboard(Map<String, Object> open, Map<String, Object> extra, Map<String, Object> cancel) {
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        List<Map<String, Object>> first = new ArrayList<>();
        if (open != null) first.add(open);
        if (extra != null) first.add(extra);
        if (!first.isEmpty()) rows.add(first);
        rows.add(List.of(cancel));
        return Map.of("inline_keyboard", rows);
    }

    /** Telegram «Ulashish»: do'stlarga yoki guruhga — bot havolasi (ochganda e'lon kartasi va «Taklif berish») */
    String shareUrl(Order o) {
        String link = client.botUsername() != null ? client.deepLink("o" + o.getId()) : notifications.siteUrl("#/orders/" + o.getId());
        String text = OrderCard.publicTitle(o.getTitle()) + " — " + OrderCard.place(o.getFromRegion(), o.getFromDistrict()) + " → "
                + OrderCard.place(o.getToRegion(), o.getToDistrict());
        return "https://t.me/share/url?url=" + enc(link) + "&text=" + enc(text);
    }

    /** Reysni guruh yoki tanishlarga ulashish: shu yo'nalishdagi reyslar sahifasi */
    String tripShareUrl(Trip t) {
        String hash = "#/trips?from=" + t.getFromRegion().getId() + "&to=" + t.getToRegion().getId()
                + (t.getFromDistrict() != null ? "&fd=" + t.getFromDistrict().getId() : "");
        String text = "🚗 " + tripWhen(t) + ": " + OrderCard.place(t.getFromRegion(), t.getFromDistrict()) + " → "
                + OrderCard.place(t.getToRegion(), t.getToDistrict())
                + (t.getSeats() != null ? ", " + t.getSeats() + " ta bo'sh o'rin" : "") + ". Yo'lovchi, pochta yoki yuk olaman:";
        return "https://t.me/share/url?url=" + enc(notifications.siteUrl(hash)) + "&text=" + enc(text);
    }

    /**
     * «❌ Bekor qilish» (oc:ID — e'lon), «❌ O'chirish» (tc:ID — reys) va sanasi o'tgan e'lon eslatmasidagi
     * «📅 Bugunga» (ek:ID) / «📅 Ertaga» (et:ID): faqat egasi
     */
    void onPostCallback(String callbackId, long chatId, int promptId, String kind, long id) {
        User u = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).orElse(null);
        if (u == null || !u.isActive()) {
            client.answerCallback(callbackId, "Hisob topilmadi");
            return;
        }
        // Javoblar saqlangandan keyin: Telegramga yuborish vaqtida e'lon qatori bloklanib turmaydi
        try {
            if ("oc".equals(kind)) {
                Order o = orders.cancelFromBot(u, id);
                String html = "❌ <b>E'lon bekor qilindi</b> (#" + o.getId() + ")\n\n" + OrderCard.text(o)
                        + "\n\nYangi e'lon uchun shunchaki qayta yozing.";
                afterCommit(() -> {
                    client.answerCallback(callbackId, "E'lon bekor qilindi");
                    client.editMessage(chatId, promptId, html, null);
                });
            } else if ("ek".equals(kind) || "et".equals(kind)) {
                boolean tomorrow = "et".equals(kind);
                Order o = orders.rescheduleFromBot(u, id, LocalDate.now(TZ).plusDays(tomorrow ? 1 : 0));
                String html = "✅ <b>E'lon " + (tomorrow ? "ertaga" : "bugunga") + " ko'chirildi</b> (#" + o.getId() + ")\n\n"
                        + OrderCard.text(o) + "\n\n📩 Haydovchi «Olaman» desa yoki narx taklif qilsa — shu yerga xabar keladi.";
                afterCommit(() -> {
                    client.answerCallback(callbackId, tomorrow ? "Ertaga ko'chirildi" : "Bugunga ko'chirildi");
                    client.editMessage(chatId, promptId, html, null);
                });
            } else {
                boolean deleted = trips.deleteAs(u, id);
                afterCommit(() -> {
                    client.answerCallback(callbackId, deleted ? "Reys o'chirildi" : "Reys allaqachon o'chirilgan");
                    client.editMessage(chatId, promptId, "❌ <b>Reys o'chirildi.</b> Yangi reys uchun shunchaki qayta yozing.", null);
                });
            }
        } catch (ApiException e) {
            String msg = e.getMessage();
            afterCommit(() -> client.answerCallback(callbackId, msg));
        }
    }

    /** Haydovchi e'loni: "Reys e'lon qilmoqchimisiz?" — yo'nalish, sana va bo'sh o'rinlar */
    static String tripSummary(LoadTextParser.Parsed p) {
        return tripHead(p) + "\n\n👇 Formani oching — reys e'lon qilinsa, shu yo'nalishda yo'lovchi, pochta yoki yuk kutayotganlarga "
                + "avtomatik xabar boradi, yangi e'lonlar esa sizga keladi.";
    }

    static String tripHead(LoadTextParser.Parsed p) {
        StringBuilder sb = new StringBuilder("🚗 <b>Reys e'lon qilmoqchimisiz?</b> Men shunday tushundim:\n\n");
        sb.append("📍 <b>Qayerdan:</b> ").append(esc(p.from().name())).append('\n');
        sb.append("🏁 <b>Qayerga:</b> ").append(esc(p.to().name()));
        if (p.daily()) sb.append("\n🔁 Har kuni");
        if (p.pickupDate() != null && !p.daily()) sb.append("\n📅 ").append(OrderCard.dateLabel(p.pickupDate()));
        if (p.departTime() != null) sb.append("\n🕒 ").append(p.departTime().format(HM));
        if (p.seats() != null) sb.append("\n💺 ").append(p.seats()).append(" ta bo'sh o'rin");
        return sb.toString();
    }

    /** Reys formasi: "/trips?new=1&fr=..&fd=..&tr=..&td=..&d=..&s=.." */
    static String tripPrefillRoute(LoadTextParser.Parsed p) {
        StringBuilder q = new StringBuilder("/trips?new=1");
        q.append("&fr=").append(p.from().regionId());
        if (p.from().districtId() != null) q.append("&fd=").append(p.from().districtId());
        q.append("&tr=").append(p.to().regionId());
        if (p.to().districtId() != null) q.append("&td=").append(p.to().districtId());
        if (p.pickupDate() != null) q.append("&d=").append(p.pickupDate());
        if (p.seats() != null) q.append("&s=").append(p.seats());
        return q.toString();
    }

    static String loadSummary(LoadTextParser.Parsed p) {
        OrderKind kind = p.kind() == null ? OrderKind.CARGO : p.kind();
        return loadHead(p) + "\n\n" + (kind == OrderKind.PASSENGER
                ? "👇 Formani oching — ma'lumotlar to'ldirilgan. Tekshiring va «E'lon qilish» ni bosing: shu yo'nalishdagi haydovchilarga "
                        + "darhol xabar boradi. Telefon raqamingiz e'londa ko'rinmaydi."
                : "👇 Formani oching — ma'lumotlar to'ldirilgan. Tekshiring va «E'lon qilish» ni bosing. "
                        + "Telefon raqamingiz e'londa ko'rinmaydi.");
    }

    /** Matndan tushunilgani: tur, yo'nalish, tafsilotlar va narx */
    static String loadHead(LoadTextParser.Parsed p) {
        OrderKind kind = p.kind() == null ? OrderKind.CARGO : p.kind();
        StringBuilder sb = new StringBuilder(switch (kind) {
            case PASSENGER -> "🧍 <b>Yo'lovchi sifatida e'lon bermoqchimisiz?</b> Men shunday tushundim:\n\n";
            case PARCEL -> "✉️ <b>Pochta yubormoqchimisiz?</b> Men shunday tushundim:\n\n";
            default -> "📝 <b>Yuk e'lon qilmoqchimisiz?</b> Men shunday tushundim:\n\n";
        });
        sb.append("📍 <b>Qayerdan:</b> ").append(esc(p.from().name())).append('\n');
        sb.append("🏁 <b>Qayerga:</b> ").append(esc(p.to().name())).append('\n');
        List<String> parts = new ArrayList<>();
        if (kind == OrderKind.PASSENGER) {
            parts.add("👥 " + (p.seats() == null ? 1 : p.seats()) + " kishi");
        } else {
            if (kind == OrderKind.CARGO && p.cargoType() != null && p.cargoType() != uz.yukgo.order.CargoType.GENERAL) {
                parts.add("📦 " + OrderCard.cargoLabel(p.cargoType()));
            }
            if (p.weightKg() != null) parts.add("⚖️ " + OrderCard.weight(p.weightKg()));
            if (kind == OrderKind.CARGO && p.bodyType() != null) parts.add("🚚 " + OrderCard.bodyLabel(p.bodyType()));
        }
        if (!parts.isEmpty()) sb.append(String.join(" · ", parts)).append('\n');
        if (p.pickupDate() != null) sb.append("📅 ").append(OrderCard.dateLabel(p.pickupDate())).append('\n');
        sb.append("💰 ").append(p.price() != null ? OrderCard.money(p.price()) : p.negotiable() ? "Narx kelishiladi" : "Narx ko'rsatilmagan");
        return sb.toString();
    }

    /** Ilovadagi forma manzili: "/new?bot=1&fr=..&tr=..&w=..&t=.." (qiymatlar %20 bilan kodlanadi — "+" emas) */
    static String prefillRoute(LoadTextParser.Parsed p, String title, String description) {
        StringBuilder q = new StringBuilder("/new?bot=1");
        q.append("&fr=").append(p.from().regionId());
        if (p.from().districtId() != null) q.append("&fd=").append(p.from().districtId());
        q.append("&tr=").append(p.to().regionId());
        if (p.to().districtId() != null) q.append("&td=").append(p.to().districtId());
        if (p.weightKg() != null) {
            double w = p.weightKg();
            q.append("&w=").append(w == Math.floor(w) ? String.valueOf((long) w) : String.valueOf(w));
        }
        if (p.price() != null) q.append("&p=").append(p.price());
        if (p.negotiable()) q.append("&n=1");
        if (p.pickupDate() != null) q.append("&d=").append(p.pickupDate());
        if (p.bodyType() != null) q.append("&b=").append(p.bodyType().canonical().name());
        if (p.cargoType() != null) q.append("&c=").append(p.cargoType().name());
        if (p.kind() != null && p.kind() != OrderKind.CARGO) q.append("&k=").append(p.kind().name());
        if (p.seats() != null && p.kind() == OrderKind.PASSENGER) q.append("&s=").append(p.seats());
        if (title != null && !title.isBlank()) q.append("&t=").append(enc(title));
        if (description != null && !description.isBlank()) q.append("&x=").append(enc(description));
        return q.toString();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A");
    }

    // =====================================================================
    // Ommaviy xabar: admin botga yuboradi → "Hammaga yuborilsinmi?" → tasdiqlasa hamma foydalanuvchiga nusxa
    // =====================================================================

    /** Faol admin yoki egaga tegishli Telegram (aks holda null — oddiy foydalanuvchi hech kimga xabar tarqata olmaydi) */
    private User staffByChat(long chatId) {
        return userRepository.findByTelegramChatId(chatId).stream()
                .filter(u -> u.isActive() && u.getRole().isStaff())
                .findFirst().orElse(null);
    }

    /** Hammaga tarqatsa bo'ladigan xabar turi (adminga ko'rsatiladigan nomi) yoki null */
    static String broadcastKind(JsonNode msg) {
        if (msg.has("photo")) return "🖼 Rasm";
        if (msg.has("video_note")) return "⭕️ Dumaloq video";
        if (msg.has("video")) return "🎬 Video";
        if (msg.has("animation")) return "🎞 GIF";
        if (msg.has("voice")) return "🎤 Ovozli xabar";
        if (msg.has("audio")) return "🎵 Audio";
        if (msg.has("document")) return "📎 Fayl";
        String text = msg.path("text").asText("").trim();
        if (!text.isEmpty() && !text.startsWith("/")) return "💬 Matn";
        return null;
    }

    /**
     * Rasm, video, dumaloq video yoki ovozli xabar — admin emas (yoki Telegrami hisobga ulanmagan) odamdan: nega hammaga
     * ketmasligini tushuntiramiz. Ko'p uchraydigan holat — baza yangilangach ega hisobi qayta yaratilgan va Telegram hali ulanmagan.
     */
    void onMediaFromNonStaff(long chatId) {
        List<User> accounts = userRepository.findByTelegramChatId(chatId);
        if (accounts.isEmpty()) {
            client.sendNow(chatId, "📎 Rasm, video, dumaloq video va ovozli xabarni hammaga faqat YukGo egasi yoki admini yubora oladi.\n\n"
                    + "Siz ega yoki admin bo'lsangiz — bu Telegram hali hisobingizga ulanmagan. Pastdagi <b>«📱 Telefon raqamni yuborish»</b> "
                    + "tugmasini bosing (raqam hisobingizdagi raqam bilan bir xil bo'lsin), keyin xabarni qayta yuboring.",
                    TelegramClient.contactKeyboard());
        } else if (accounts.stream().anyMatch(u -> u.getRole().isStaff() && !u.isActive())) {
            client.sendNow(chatId, "⛔ Admin hisobingiz faol emas — ommaviy xabar yuborib bo'lmaydi.", TelegramClient.mainMenu());
        } else {
            client.sendNow(chatId, "📎 Rasm, video va ovozli xabarni hammaga faqat YukGo admini yubora oladi. "
                    + "E'lon bermoqchi bo'lsangiz — matn bilan yozing, masalan: <i>Kosonsoydan Toshkentga 2 kishi, ertaga</i>",
                    TelegramClient.mainMenu());
        }
    }

    private void onBroadcastDraft(long chatId, JsonNode msg) {
        int messageId = msg.path("message_id").asInt();
        String caption = msg.path("caption").asText(msg.path("text").asText(""));
        String group = msg.path("media_group_id").asText("");
        if (!group.isEmpty()) {
            AlbumBuffer b = albums.computeIfAbsent(chatId + ":" + group, k -> new AlbumBuffer(chatId));
            b.messageIds.add(messageId);
            if (b.caption == null || b.caption.isBlank()) b.caption = caption;
            b.lastAt = System.currentTimeMillis();
            return;
        }
        String text = msg.has("text") ? msg.path("text").asText("").trim() : null;
        askToBroadcast(new Draft(chatId, List.of(messageId), describe(broadcastKind(msg), caption), Instant.now(), text));
    }

    /** To'liq kelgan albomlar uchun bittadan so'rov chiqaradi */
    private void flushAlbums(long nowMs) {
        for (var e : albums.entrySet()) {
            AlbumBuffer b = e.getValue();
            if (nowMs - b.lastAt < ALBUM_WAIT_MS) continue;
            if (albums.remove(e.getKey()) == null) continue;
            List<Integer> ids = b.messageIds.stream().sorted().toList();
            Draft d = new Draft(b.chatId, ids, describe("🖼 Albom (" + ids.size() + " ta rasm/video)", b.caption), Instant.now(), null);
            try {
                tx.executeWithoutResult(st -> askToBroadcast(d));
            } catch (Exception ex) {
                log.error("Albom so'rovini yuborishda xato", ex);
            }
        }
    }

    private static String describe(String kind, String caption) {
        String c = caption == null ? "" : caption.trim();
        if (c.length() > 120) c = c.substring(0, 120) + "…";
        return "<b>" + kind + "</b>" + (c.isEmpty() ? "" : ": «" + esc(c) + "»");
    }

    private void askToBroadcast(Draft d) {
        Instant expiredBefore = Instant.now().minus(DRAFT_TTL);
        drafts.values().removeIf(x -> x.createdAt().isBefore(expiredBefore));
        String id = Long.toString(RANDOM.nextLong() & Long.MAX_VALUE, 36);
        drafts.put(id, d);
        int recipients = recipientsFor(d.chatId()).size();
        List<List<Map<String, Object>>> rows = new ArrayList<>();
        rows.add(List.of(
                Map.of("text", "✅ Hammaga yuborish", "callback_data", "bc:" + id + ":go"),
                Map.of("text", "❌ Bekor qilish", "callback_data", "bc:" + id + ":no")));
        // Admin ham e'lon bera olsin: matn e'longa o'xshasa — «E'lon sifatida joylash»
        boolean looksLikeLoad = d.text() != null && parse(d.text()) != null;
        if (looksLikeLoad) rows.add(List.of(Map.of("text", "📝 E'lon sifatida joylash", "callback_data", "bc:" + id + ":post")));
        Integer sent = client.sendReply(d.chatId(), "📢 <b>Ommaviy xabar</b>\n\n" + d.description() + "\n\n"
                + "Shu xabar barcha foydalanuvchilarga (<b>" + recipients + "</b> kishi) xuddi shu ko'rinishda yuborilsinmi?"
                + (looksLikeLoad ? "\n\nYoki bu — e'lonmi? Unda «📝 E'lon sifatida joylash» ni bosing." : ""),
                Map.of("inline_keyboard", rows), d.messageIds().get(0));
        if (sent == null) drafts.remove(id);
    }

    /** Botni ishga tushirgan barcha faol foydalanuvchilar (adminning o'zidan tashqari) */
    private List<Long> recipientsFor(long adminChatId) {
        return userRepository.findAllActiveChatIds().stream().filter(c -> c != adminChatId).toList();
    }

    void onCallback(JsonNode cq) {
        String callbackId = cq.path("id").asText(null);
        String data = cq.path("data").asText("");
        long chatId = cq.path("message").path("chat").path("id").asLong();
        int promptId = cq.path("message").path("message_id").asInt();
        String[] parts = data.split(":");
        if (parts.length == 2 && Set.of("oc", "tc", "ek", "et").contains(parts[0]) && parts[1].matches("\\d{1,18}")) {
            onPostCallback(callbackId, chatId, promptId, parts[0], Long.parseLong(parts[1]));
            return;
        }
        if (parts.length == 2 && "op".equals(parts[0]) && parts[1].matches("[a-z0-9]{1,16}")) {
            onConfirmPost(callbackId, chatId, promptId, parts[1]);
            return;
        }
        if (parts.length != 3 || !"bc".equals(parts[0])) {
            client.answerCallback(callbackId, null);
            return;
        }
        Draft d = drafts.get(parts[1]);
        boolean expired = d != null && d.createdAt().isBefore(Instant.now().minus(DRAFT_TTL));
        if (d == null || expired || d.chatId() != chatId) {
            if (expired) drafts.remove(parts[1]);
            client.answerCallback(callbackId, "So'rov eskirgan — xabarni qaytadan yuboring");
            client.editMessage(chatId, promptId, "⌛ So'rov eskirgan. Xabarni botga qaytadan yuboring.", null);
            return;
        }
        User staff = staffByChat(chatId);
        if (staff == null) {
            drafts.remove(parts[1]);
            client.answerCallback(callbackId, "Ruxsat yo'q");
            client.editMessage(chatId, promptId, "⛔ Ommaviy xabarni faqat admin yubora oladi.", null);
            return;
        }
        if ("no".equals(parts[2])) {
            drafts.remove(parts[1]);
            client.answerCallback(callbackId, "Bekor qilindi");
            client.editMessage(chatId, promptId, "❌ Bekor qilindi — hech kimga yuborilmadi.\n\n" + d.description(), null);
            return;
        }
        if ("post".equals(parts[2])) {
            if (drafts.remove(parts[1]) == null) {
                client.answerCallback(callbackId, null);
                return;
            }
            client.answerCallback(callbackId, "E'lon joylanmoqda");
            client.editMessage(chatId, promptId, "📝 Ommaviy xabar yuborilmadi — matn e'lon sifatida joylanmoqda.\n\n" + d.description(), null);
            onFreeText(chatId, d.text() == null ? "" : d.text(), true);
            return;
        }
        if (!"go".equals(parts[2]) || drafts.remove(parts[1]) == null) {   // ikki marta bosilsa — bir marta yuboriladi
            client.answerCallback(callbackId, null);
            return;
        }
        List<Long> chats = recipientsFor(chatId);
        AdminLog l = new AdminLog();
        l.setActorId(staff.getId());
        l.setActorName(staff.getFullName() + " (" + staff.getRole() + ")");
        l.setAction("BROADCAST");
        String details = "Telegram orqali: " + d.description().replaceAll("<[^>]+>", "") + " → " + chats.size() + " kishi";
        l.setDetails(details.length() > 500 ? details.substring(0, 500) : details);
        adminLogs.save(l);
        client.answerCallback(callbackId, "Yuborish boshlandi");
        client.editMessage(chatId, promptId, "⏳ <b>" + chats.size() + "</b> kishiga yuborilmoqda…\n\n" + d.description(), null);
        client.broadcastCopy(chats, chatId, d.messageIds(), chatId, promptId);
    }

    private void onStart(long chatId, String param) {
        if (!param.isEmpty()) {
            VerificationRequest vr = verificationService.findByToken(param);
            if (vr != null && !vr.isConsumed()) {
                client.sendNow(chatId,
                        "👋 <b>YukGo</b>ga xush kelibsiz!\n\nSaytda <b>" + mask(vr.getPhone()) + "</b> raqami kiritildi. "
                                + "Tasdiqlash kodini olish uchun pastdagi <b>«📱 Telefon raqamni yuborish»</b> tugmasini bosing.\n\n"
                                + "⚠️ Telegramdagi raqamingiz saytda kiritilgan raqam bilan bir xil bo'lishi kerak.",
                        TelegramClient.contactKeyboard());
                return;
            }
            Matcher m;
            if ((m = ORDER_START.matcher(param)).matches()) {
                onOrderStart(chatId, Long.parseLong(m.group(1)), m.group(2));
                return;
            }
            if ((m = NEW_START.matcher(param)).matches()) {
                onNewStart(chatId, m.group(1));
                return;
            }
            if ((m = GROUP_START.matcher(param)).matches()) {
                onGroupStart(chatId, m.group(1));
                return;
            }
            if ((m = SRC_START.matcher(param)).matches()) {
                onWelcome(chatId, m.group(1), null);
                return;
            }
            if ((m = REF_START.matcher(param)).matches()) {
                onWelcome(chatId, null, m.group(1));
                return;
            }
        }
        onWelcome(chatId, null, null);
    }

    private void onWelcome(long chatId, String src, String ref) {
        boolean linked = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).isPresent();
        String about = "👋 <b>YukGo</b> — viloyatdan viloyatga va shahar ichida yuk, pochta va yo'lovchi tashish xizmati.\n\n"
                + "🚚 Haydovchilar — yo'nalishiga mos yuk, pochta va yo'lovchi topadi, xabar o'zi keladi\n"
                + "📦 Yuk egalari — ishonchli haydovchi topadi va yukini xaritada kuzatadi\n"
                + "🧍 Yo'lovchilar — o'z tumanidan ketadigan haydovchini topadi\n\n";
        if (!linked && (src != null || ref != null)) {
            // Havola bilan kelgan yangi foydalanuvchi: ilovani manba bilan ochadigan tugma, keyin raqamni ulash
            growth.rememberStart(chatId, src, ref);
            client.sendNowWithFallbacks(chatId, (ref != null ? "🤝 Sizni YukGo'ga taklif qilishdi!\n\n" : "") + about
                            + "📱 Boshlash uchun pastdagi tugmani bosing — ro'yxatdan o'tish 1 daqiqa.",
                    appButtons("🚀 YukGo'ni ochish", "#/register", src, ref));
            client.sendNow(chatId, "Telefon raqamingizni yuborsangiz, buyurtmalar haqidagi xabarlar shu yerga keladi 👇",
                    TelegramClient.contactKeyboard());
            return;
        }
        client.sendNow(chatId, about
                        + "📱 Ilovani shu yerning o'zida ochish uchun pastdagi <b>«YukGo»</b> tugmasini bosing.\n\n"
                        + (linked ? "Hisobingiz botga ulangan ✅" : "Hisobni ulash uchun telefon raqamingizni yuboring 👇"),
                linked ? TelegramClient.mainMenu() : TelegramClient.contactKeyboard());
    }

    /** Kanal postidagi "📩 Taklif berish": buyurtma kartasi (ism-telefonsiz) va uni ilovada ochish tugmasi */
    void onOrderStart(long chatId, long orderId, String src) {
        boolean linked = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).isPresent();
        if (!linked) growth.rememberStart(chatId, src, null);
        Order o = orderRepository.findById(orderId).orElse(null);
        if (o == null || o.getStatus() != OrderStatus.NEW) {
            client.sendNowWithFallbacks(chatId, "😔 Bu yuk uchun haydovchi allaqachon topilgan yoki e'lon yopilgan.\n\n"
                    + "Boshqa ochiq buyurtmalarni ko'ring 👇", appButtons("📦 Ochiq buyurtmalar", "#/", src, null));
            return;
        }
        String html = OrderCard.text(o) + "\n\n" + (linked
                ? "👇 Narxingizni taklif qiling yoki yuk egasi narxiga rozi bo'ling."
                : "👇 Taklif berish uchun ilovani oching. Birinchi marta — 1 daqiqada ro'yxatdan o'tasiz.");
        client.sendNowWithFallbacks(chatId, html, appButtons("📩 Taklif berish", "#/orders/" + o.getId(), src, null));
    }

    /** Kanal postidagi "➕ Yuk e'lon qilish" */
    private void onNewStart(long chatId, String src) {
        if (userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).isEmpty()) growth.rememberStart(chatId, src, null);
        client.sendNowWithFallbacks(chatId, "📦 <b>E'lon berish</b>\n\n"
                        + "Eng tezi — shu yerga <b>bitta xabarda</b> yozing: hisobingiz botga ulangan bo'lsa, e'lon darhol "
                        + "saytga chiqadi (bo'lmasa — formani o'zim to'ldirib beraman). Masalan:\n"
                        + "• <i>Toshkent - Samarqand, 5 tonna mebel, ertaga, 3 mln</i>\n"
                        + "• <i>Kosonsoydan Toshkentga 2 kishi, ertaga</i>\n"
                        + "• <i>Andijon - Toshkent, pochta, bugun</i>\n\n"
                        + "Yoki formani hozir oching 👇",
                appButtons("➕ Formani ochish", "#/new", src, null));
    }

    /** Mini App tugmasi (manba bilan), Telegram rad etsa — oddiy havola */
    private List<Object> appButtons(String text, String hashLink, String src, String ref) {
        List<Object> list = new ArrayList<>();
        list.add(TelegramClient.appButton(text, notifications.appUrl(hashLink, src, ref)));
        list.add(TelegramClient.linkButton(text, notifications.siteUrl(hashLink, src, ref)));
        return list;
    }

    private void onContact(long chatId, long fromId, String username, JsonNode contact) {
        long contactUserId = contact.path("user_id").asLong(0);
        if (contactUserId != fromId) {
            client.sendNow(chatId, "❌ Faqat <b>o'zingizning</b> raqamingizni tugma orqali yuboring.", TelegramClient.contactKeyboard());
            return;
        }
        String phone = Phones.tryNormalize(contact.path("phone_number").asText());
        if (phone == null) {
            client.sendNow(chatId, "❌ Faqat O'zbekiston (+998) raqamlari qo'llab-quvvatlanadi.", null);
            return;
        }
        int sent = verificationService.onContactLinked(phone, chatId, fromId, username);
        linkUserAccount(phone, chatId);
        if (sent == 0) {
            client.sendNow(chatId, "✅ Raqamingiz <b>" + phone + "</b> botga ulandi.\n"
                    + "Endi barcha bildirishnomalar shu yerga keladi.", TelegramClient.mainMenu());
        }
        // sent > 0 bo'lsa kod alohida xabarda yuborilgan
    }

    void linkUserAccount(String phone, long chatId) {
        userRepository.findByPhone(phone).ifPresent(u -> {
            u.setTelegramChatId(chatId);
            userRepository.save(u);
        });
    }

    private void onCode(long chatId) {
        if (!verificationService.resendForChat(chatId)) {
            client.sendNow(chatId, "Hozir sizda ochiq tasdiqlash so'rovi yo'q. Avval saytda telefon raqamingizni kiriting.",
                    TelegramClient.mainMenu());
        }
    }

    private void onMyOrders(long chatId) {
        User u = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).orElse(null);
        if (u == null) {
            client.sendNow(chatId, "Siz hali ro'yxatdan o'tmagansiz. Ro'yxatdan o'ting:", openButton("Ro'yxatdan o'tish", "#/register"));
            return;
        }
        List<Order> orders = orderRepository.findActiveForUser(u.getId(),
                List.of(OrderStatus.NEW, OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT));
        if (orders.isEmpty()) {
            client.sendNow(chatId, "Faol buyurtmalaringiz yo'q.", TelegramClient.mainMenu());
            return;
        }
        StringBuilder sb = new StringBuilder("📦 <b>Faol buyurtmalaringiz:</b>\n\n");
        for (Order o : orders.stream().limit(10).toList()) {
            boolean asDriver = o.getDriver() != null && o.getDriver().getId().equals(u.getId());
            sb.append("#").append(o.getId()).append(" ").append(esc(o.getTitle())).append("\n")
                    .append("   ").append(esc(o.getFromRegion().getName())).append(" → ").append(esc(o.getToRegion().getName())).append("\n")
                    .append("   ").append(statusLabel(o.getStatus())).append(asDriver ? " · siz haydovchisiz" : "")
                    .append("\n\n");
        }
        client.sendNow(chatId, sb.toString(), openButton("Buyurtmalarni ochish", "#/my"));
    }

    private void onProfile(long chatId) {
        User u = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).orElse(null);
        if (u == null) {
            client.sendNow(chatId, "Hisob topilmadi. Avval ro'yxatdan o'ting.", null);
            return;
        }
        String text = "👤 <b>" + esc(u.getFullName()) + "</b>\n"
                + "📱 " + u.getPhone() + "\n"
                + "⭐ Reyting: " + u.getRatingAvg() + " (" + u.getRatingCount() + " ta baho)\n"
                + "📦 Yaratgan buyurtmalar: " + u.getOrdersCreated() + "\n"
                + "🚚 Yetkazgan buyurtmalar: " + u.getDeliveriesCompleted()
                + (u.isDriver() ? "\n🚗 " + esc(u.getVehicle().getBrand()) + " " + esc(u.getVehicle().getModel() == null ? "" : u.getVehicle().getModel())
                + " · " + esc(u.getVehicle().getPlateNumber()) : "");
        client.sendNow(chatId, text, openButton("Profilni ochish", "#/profile"));
    }

    /** Mini App tugmasi (Telegram ichida ochiladi); PUBLIC_URL https bo'lmasa — oddiy havola. */
    private Map<String, Object> openButton(String text, String hashLink) {
        Map<String, Object> app = TelegramClient.appButton(text, notifications.appUrl(hashLink));
        return app != null ? app : TelegramClient.linkButton(text, notifications.siteUrl(hashLink));
    }

    private void onHelp(long chatId) {
        if (staffByChat(chatId) != null) {
            client.sendNow(chatId, "🛡 <b>Admin uchun:</b> botga matn, rasm, video, dumaloq video, ovozli xabar, fayl "
                    + "yoki albom yuboring — «✅ Hammaga yuborish» ni bosganingizdan keyin u barcha foydalanuvchilarga boradi.", null);
        }
        client.sendNow(chatId, """
                ℹ️ <b>YukGo qanday ishlaydi?</b>

                <b>Yuk, pochta yoki yo'lovchi e'loni:</b>
                1. Qayerdan, qayerga va qachonligini yozing (yo'lovchi — necha kishi)
                2. Shu yo'nalishdagi haydovchilarga darhol xabar boradi
                3. Haydovchilar ilovada yozishib savol berishi mumkin (raqamingiz yashirin), «Olaman» desa — bir-biringizning raqamini ko'rasiz
                4. Yukni xaritada jonli kuzatasiz, qabul qiluvchi 4 xonali kodni aytadi
                5. Haydovchini baholaysiz ⭐

                <b>Haydovchi uchun:</b>
                1. Profilga mashinangizni qo'shing
                2. Reys e'lon qiling (masalan, ertaga Kosonsoy → Toshkent yoki «har kuni»)
                3. Yo'nalishingizga mos yo'lovchi, pochta yoki yuk chiqsa — shu botga xabar keladi
                4. Ilova ichidagi Yandex xarita bilan yo'lni toping

                <b>Tez e'lon:</b> botga shunchaki yozing — <i>Kosonsoydan Toshkentga 2 kishi, ertaga</i> — e'lon darhol saytga chiqadi.
                Haydovchi: <i>Kosonsoy - Toshkent, ertaga 07:00, 3 ta joy bor</i> — reys chiqadi.

                Savollar bo'lsa — saytdagi "Shikoyat/murojaat" bo'limi orqali yozing.""",
                TelegramClient.mainMenu());
    }

    private static String statusLabel(OrderStatus s) {
        return switch (s) {
            case NEW -> "🟡 Haydovchi kutilmoqda";
            case ACCEPTED -> "🔵 Haydovchi yukni olishga ketyapti";
            case IN_TRANSIT -> "🚚 Yo'lda";
            case DELIVERED -> "✅ Yetkazildi";
            case CANCELLED -> "❌ Bekor qilingan";
        };
    }

    private static String mask(String phone) {
        return phone.length() < 8 ? phone : phone.substring(0, 6) + " *** ** " + phone.substring(phone.length() - 2);
    }
}
