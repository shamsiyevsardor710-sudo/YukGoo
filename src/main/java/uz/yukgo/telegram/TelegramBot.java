package uz.yukgo.telegram;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import uz.yukgo.auth.VerificationRequest;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.common.Phones;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.util.List;
import java.util.Map;

import static uz.yukgo.telegram.TelegramClient.esc;

/**
 * YukGo Telegram boti (long polling).
 *  • /start TOKEN — saytdan kelgan foydalanuvchi: kontakt so'raladi va tasdiqlash kodi yuboriladi
 *  • kontakt ulashish — raqam botga bog'lanadi
 *  • 📦 Buyurtmalarim, ⭐ Profilim, 🔑 Kod olish, ℹ️ Yordam
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
    private final org.springframework.transaction.support.TransactionTemplate tx;

    private volatile boolean running;
    private Thread worker;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!client.enabled()) {
            log.warn("TELEGRAM_BOT_TOKEN berilmagan — bot o'chiq. Tasdiqlash kodlari logga yoziladi.");
            return;
        }
        client.deleteWebhook();
        client.setMyCommands(List.of(
                Map.of("command", "start", "description", "Botni ishga tushirish"),
                Map.of("command", "buyurtmalar", "description", "Faol buyurtmalarim"),
                Map.of("command", "kod", "description", "Tasdiqlash kodini qayta olish"),
                Map.of("command", "yordam", "description", "Yordam")));
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
        while (running) {
            try {
                JsonNode res = client.getUpdates(offset, 25);
                if (res == null || !res.path("ok").asBoolean()) {
                    Thread.sleep(3000);
                    continue;
                }
                for (JsonNode upd : res.path("result")) {
                    offset = upd.path("update_id").asLong() + 1;
                    try {
                        JsonNode m = upd.path("message");
                        tx.executeWithoutResult(st -> handle(m));
                    } catch (Exception e) {
                        log.error("Bot xabarini qayta ishlashda xato", e);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                log.warn("Telegram polling xatosi: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
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
        } else {
            client.sendNow(chatId, "Quyidagi tugmalardan foydalaning 👇", TelegramClient.mainMenu());
        }
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
            client.sendNow(chatId, "Siz hali ro'yxatdan o'tmagansiz. Saytda ro'yxatdan o'ting:", TelegramClient.inlineUrlButton(
                    "Ro'yxatdan o'tish", notifications.siteUrl("#/register")));
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
        client.sendNow(chatId, sb.toString(), TelegramClient.inlineUrlButton("Saytda ochish", notifications.siteUrl("#/my")));
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
        client.sendNow(chatId, text, TelegramClient.inlineUrlButton("Profilni ochish", notifications.siteUrl("#/profile")));
    }

    private void onHelp(long chatId) {
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
