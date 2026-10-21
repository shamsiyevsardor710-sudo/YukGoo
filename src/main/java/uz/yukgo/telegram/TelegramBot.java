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
import uz.yukgo.common.Phones;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static uz.yukgo.telegram.TelegramClient.esc;

/**
 * YukGo Telegram boti (long polling).
 *  • /start TOKEN — saytdan kelgan foydalanuvchi: kontakt so'raladi va tasdiqlash kodi yuboriladi
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

    private volatile boolean running;
    private Thread worker;

    // ---- Ommaviy xabar (adminlar uchun) ----
    private static final SecureRandom RANDOM = new SecureRandom();
    /** Tasdiq kutayotgan so'rov shundan keyin eskiradi */
    private static final Duration DRAFT_TTL = Duration.ofHours(1);
    /** Albomning oxirgi qismi kelgandan keyin shuncha kutib, bitta so'rov chiqariladi */
    private static final long ALBUM_WAIT_MS = 1500;

    /** Admin yuborgan, "Hammaga yuborish" tasdig'ini kutayotgan xabar(lar) */
    record Draft(long chatId, List<Integer> messageIds, String description, Instant createdAt) {}

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
                    log.warn("Telegram polling xatosi: {}", e.getMessage());
                }
                if (!pause(5000)) return;
            } catch (Exception e) {
                log.warn("Telegram polling xatosi: {}", e.getMessage());
                if (!pause(5000)) return;
            }
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
        if (!"private".equals(msg.path("chat").path("type").asText())) return;
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
        } else {
            client.sendNow(chatId, "Quyidagi tugmalardan foydalaning 👇", TelegramClient.mainMenu());
        }
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
        askToBroadcast(new Draft(chatId, List.of(messageId), describe(broadcastKind(msg), caption), Instant.now()));
    }

    /** To'liq kelgan albomlar uchun bittadan so'rov chiqaradi */
    private void flushAlbums(long nowMs) {
        for (var e : albums.entrySet()) {
            AlbumBuffer b = e.getValue();
            if (nowMs - b.lastAt < ALBUM_WAIT_MS) continue;
            if (albums.remove(e.getKey()) == null) continue;
            List<Integer> ids = b.messageIds.stream().sorted().toList();
            Draft d = new Draft(b.chatId, ids, describe("🖼 Albom (" + ids.size() + " ta rasm/video)", b.caption), Instant.now());
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
        Map<String, Object> buttons = Map.of("inline_keyboard", List.of(List.of(
                Map.of("text", "✅ Hammaga yuborish", "callback_data", "bc:" + id + ":go"),
                Map.of("text", "❌ Bekor qilish", "callback_data", "bc:" + id + ":no"))));
        Integer sent = client.sendReply(d.chatId(), "📢 <b>Ommaviy xabar</b>\n\n" + d.description() + "\n\n"
                + "Shu xabar barcha foydalanuvchilarga (<b>" + recipients + "</b> kishi) xuddi shu ko'rinishda yuborilsinmi?",
                buttons, d.messageIds().get(0));
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

    private void onStart(long chatId, String token) {
        if (!token.isEmpty()) {
            VerificationRequest vr = verificationService.findByToken(token);
            if (vr != null && !vr.isConsumed()) {
                client.sendNow(chatId,
                        "👋 <b>YukGo</b>ga xush kelibsiz!\n\nSaytda <b>" + mask(vr.getPhone()) + "</b> raqami kiritildi. "
                                + "Tasdiqlash kodini olish uchun pastdagi <b>«📱 Telefon raqamni yuborish»</b> tugmasini bosing.\n\n"
                                + "⚠️ Telegramdagi raqamingiz saytda kiritilgan raqam bilan bir xil bo'lishi kerak.",
                        TelegramClient.contactKeyboard());
                return;
            }
        }
        boolean linked = userRepository.findFirstByTelegramChatIdOrderByIdDesc(chatId).isPresent();
        client.sendNow(chatId,
                "👋 <b>YukGo</b> — viloyatdan viloyatga va shahar ichida yuk yetkazib berish xizmati.\n\n"
                        + "🚚 Haydovchilar — buyurtma topadi va daromad qiladi\n"
                        + "📦 Yuk egalari — ishonchli haydovchi topadi va yukini xaritada kuzatadi\n\n"
                        + "📱 Ilovani shu yerning o'zida ochish uchun pastdagi <b>«YukGo»</b> tugmasini bosing.\n\n"
                        + (linked ? "Hisobingiz botga ulangan ✅" : "Hisobni ulash uchun telefon raqamingizni yuboring 👇"),
                linked ? TelegramClient.mainMenu() : TelegramClient.contactKeyboard());
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

                <b>Yuk egasi uchun:</b>
                1. Saytda buyurtma yarating (qayerdan, qayerga, yuk turi, narx)
                2. Haydovchilar narx taklif qiladi — reyting va mashinasiga qarab tanlaysiz
                3. Yukni xaritada jonli kuzatasiz
                4. Qabul qiluvchi 4 xonali kodni haydovchiga aytadi — yuk topshirildi
                5. Haydovchini baholaysiz ⭐

                <b>Haydovchi uchun:</b>
                1. Profilga mashina ma'lumotlarini kiriting
                2. Yangi buyurtmalar shu botga keladi
                3. Taklif yuboring yoki narxga rozi bo'lib darhol oling
                4. Ilova ichidagi Yandex xarita bilan yo'lni toping

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
