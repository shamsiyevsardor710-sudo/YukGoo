package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.yukgo.channel.OrderCard;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.trip.MatchService;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * Har kungi eslatmalar:
 * <ul>
 *   <li>07:00 — haydovchilarga ertalabki xulosa: bugun va ertaga yo'nalishida nechta ochiq e'lon bor;</li>
 *   <li>09:00 — sanasi kecha bo'lgan, hali hech kim olmagan e'lon egasiga: «hali kerakmi?» (bugunga yoki ertaga
 *   ko'chirish yoki yopish).</li>
 * </ul>
 * Ma'lumot qisqa o'qish tranzaksiyasida yig'iladi, xabarlar undan keyin, sekin-asta yuboriladi (Telegram cheklovi).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReminders {
    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    /** Telegram: bitta botdan sekundiga ~30 xabardan oshmasin */
    static final long PAUSE_MS = 40;
    /** Sanasiz e'lonlar — shuncha kun ichida berilganlari */
    static final Duration NO_DATE_WINDOW = Duration.ofDays(3);
    static final int TOP_ROUTES = 3;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final TelegramClient telegram;
    private final BlockService blocks;
    private final PlatformTransactionManager txManager;

    /** Bitta haydovchiga ertalabki xulosa */
    record Digest(User driver, int total, String html, String pushTitle, String pushBody) {}

    /** Sanasi o'tgan e'lon egasiga savol */
    record LatePrompt(User sender, Long chatId, Long orderId, String title, String html) {}

    // =====================================================================
    // 07:00 — ertalabki xulosa
    // =====================================================================

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Tashkent")
    public void morningDigest() {
        LocalDate today = LocalDate.now(TZ);
        List<Digest> list = readOnly(() -> digests(today, Instant.now()));
        int sent = 0;
        for (Digest d : list) {
            try {
                if (notifications.telegramOrPush(d.driver(), d.html(), d.pushTitle(), d.pushBody(), "#/")) sent++;
            } catch (Exception e) {
                log.warn("Ertalabki xabar yuborilmadi (haydovchi {}): {}", d.driver().getId(), TelegramClient.safe(e));
            }
            if (!pause()) break;
        }
        if (sent > 0) log.info("Ertalabki xulosa: {} ta haydovchiga", sent);
    }

    /** Har bir haydovchi uchun: uyi yoki ishlaydigan viloyatidan / viloyatiga, turi va mashinasiga mos ochiq e'lonlar */
    List<Digest> digests(LocalDate today, Instant now) {
        List<Order> open = orderRepository.openForDigest(today, today.plusDays(1), now.minus(NO_DATE_WINDOW));
        if (open.isEmpty()) return List.of();
        List<Digest> out = new ArrayList<>();
        for (User d : userRepository.findDriversForDigest()) {
            Set<Long> regions = new HashSet<>(d.getWorkRegionIds());
            if (d.getRegion() != null) regions.add(d.getRegion().getId());
            if (regions.isEmpty()) continue;
            Set<Long> hidden = null;
            List<Order> fit = new ArrayList<>();
            for (Order o : open) {
                if (o.getSender().getId().equals(d.getId())) continue;
                if (!regions.contains(o.getFromRegion().getId()) && !regions.contains(o.getToRegion().getId())) continue;
                if (!MatchService.kindAndVehicleFit(d, o)) continue;
                if (hidden == null) hidden = blocks.related(d.getId());
                if (hidden.contains(o.getSender().getId())) continue;
                fit.add(o);
            }
            if (!fit.isEmpty()) out.add(digest(d, fit));
        }
        return out;
    }

    static Digest digest(User d, List<Order> fit) {
        Map<OrderKind, Integer> kinds = new EnumMap<>(OrderKind.class);
        Map<String, Integer> routes = new LinkedHashMap<>();
        for (Order o : fit) {
            kinds.merge(o.getKind() == null ? OrderKind.CARGO : o.getKind(), 1, Integer::sum);
            routes.merge(o.getFromRegion().getName() + " → " + o.getToRegion().getName(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(routes.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        String kindLine = kindLine(kinds);

        StringBuilder sb = new StringBuilder("☀️ <b>Xayrli tong");
        if (d.getFirstName() != null && !d.getFirstName().isBlank()) sb.append(", ").append(TelegramClient.esc(d.getFirstName().trim()));
        sb.append("!</b>\nBugun va ertaga yo'nalishlaringizda <b>").append(fit.size()).append(" ta</b> ochiq e'lon bor:\n")
                .append(kindLine).append("\n\n");
        int shown = 0;
        for (Map.Entry<String, Integer> r : top) {
            if (shown++ == TOP_ROUTES) break;
            sb.append("📍 ").append(TelegramClient.esc(r.getKey())).append(" — ").append(r.getValue()).append(" ta\n");
        }
        int rest = top.size() - TOP_ROUTES;
        if (rest > 0) sb.append("… va yana ").append(rest).append(" ta yo'nalish\n");
        sb.append("\n👇 Ko'rish va taklif berish uchun tugmani bosing. Bunday xabarlarni profilingizda o'chirib qo'yishingiz mumkin.");

        String pushTitle = "☀️ Bugun " + fit.size() + " ta e'lon yo'nalishingizda";
        String pushBody = kindLine + (top.isEmpty() ? "" : " · " + top.get(0).getKey() + " (" + top.get(0).getValue() + ")");
        return new Digest(d, fit.size(), sb.toString(), pushTitle, pushBody);
    }

    /** «🧍 4 ta yo'lovchi · ✉️ 2 ta pochta · 📦 1 ta yuk» */
    static String kindLine(Map<OrderKind, Integer> kinds) {
        List<String> parts = new ArrayList<>();
        Integer p = kinds.get(OrderKind.PASSENGER), m = kinds.get(OrderKind.PARCEL), c = kinds.get(OrderKind.CARGO);
        if (p != null) parts.add("🧍 " + p + " ta yo'lovchi");
        if (m != null) parts.add("✉️ " + m + " ta pochta");
        if (c != null) parts.add("📦 " + c + " ta yuk");
        return String.join(" · ", parts);
    }

    // =====================================================================
    // 09:00 — sanasi o'tgan e'lon: «hali kerakmi?»
    // =====================================================================

    @Scheduled(cron = "0 0 9 * * *", zone = "Asia/Tashkent")
    public void askAboutLateOrders() {
        LocalDate yesterday = LocalDate.now(TZ).minusDays(1);
        List<LatePrompt> list = readOnly(() -> latePrompts(yesterday));
        for (LatePrompt p : list) {
            try {
                String link = "#/orders/" + p.orderId();
                notifications.inApp(p.sender(), "📅 E'lon sanasi o'tdi",
                        "«" + p.title() + "» — hali kerak bo'lsa, bugunga ko'chiring yoki e'lonni yoping.", link);
                if (p.chatId() != null) telegram.sendNowWithFallbacks(p.chatId(), p.html(), lateButtons(p.orderId(), link));
            } catch (Exception e) {
                log.warn("Sanasi o'tgan e'lon haqida so'ralmadi (#{}): {}", p.orderId(), TelegramClient.safe(e));
            }
            if (!pause()) break;
        }
        if (!list.isEmpty()) log.info("Sanasi o'tgan {} ta e'lon egasidan «hali kerakmi?» so'raldi", list.size());
    }

    List<LatePrompt> latePrompts(LocalDate day) {
        List<LatePrompt> out = new ArrayList<>();
        for (Order o : orderRepository.openOnDay(day)) {
            User s = o.getSender();
            String title = o.isPassenger() ? (o.getSeats() == null ? 1 : o.getSeats()) + " kishi, "
                    + o.getFromRegion().getName() + " → " + o.getToRegion().getName() : OrderCard.publicTitle(o.getTitle());
            String html = "📅 <b>E'loningiz sanasi o'tdi</b> — hali hech kim olmadi.\n\n" + OrderCard.text(o)
                    + "\n\nHali kerakmi? Bugunga ko'chirsangiz, e'lon yana yangilar qatorida turadi va shu yo'nalishdagi "
                    + "haydovchilarga qayta xabar boradi.";
            out.add(new LatePrompt(s, s.getTelegramChatId(), o.getId(), title, html));
        }
        return out;
    }

    /** [📅 Bugunga] [📅 Ertaga] / [❌ Kerak emas] / [👀 Ko'rish] — Mini App, oddiy havola yoki havolasiz */
    List<Object> lateButtons(Long orderId, String link) {
        List<Map<String, Object>> move = List.of(
                Map.of("text", "📅 Bugunga", "callback_data", "ek:" + orderId),
                Map.of("text", "📅 Ertaga", "callback_data", "et:" + orderId));
        List<Map<String, Object>> close = List.of(Map.of("text", "❌ Kerak emas", "callback_data", "oc:" + orderId));
        List<Object> variants = new ArrayList<>();
        String app = notifications.appUrl(link);
        String site = notifications.siteUrl(link);
        if (TelegramClient.isHttpsPublic(app)) {
            variants.add(Map.of("inline_keyboard", List.of(move, close, List.of(Map.of("text", "👀 Ko'rish", "web_app", Map.of("url", app))))));
        }
        if (TelegramClient.isPublicUrl(site)) {
            variants.add(Map.of("inline_keyboard", List.of(move, close, List.of(Map.of("text", "👀 Ko'rish", "url", site)))));
        }
        variants.add(Map.of("inline_keyboard", List.of(move, close)));
        return variants;
    }

    // =====================================================================

    private <T> List<T> readOnly(Supplier<List<T>> work) {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.setReadOnly(true);
        List<T> r = tx.execute(s -> work.get());
        return r == null ? List.of() : r;
    }

    /** false — dastur to'xtatilmoqda */
    private static boolean pause() {
        try {
            Thread.sleep(PAUSE_MS);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
