package uz.yukgo.order;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uz.yukgo.channel.OrderCard;
import uz.yukgo.notification.Msg;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.notification.TgCard;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.trip.MatchService;
import uz.yukgo.trip.Trip;
import uz.yukgo.trip.TripNotes;
import uz.yukgo.trip.TripPlanner;
import uz.yukgo.trip.TripRepository;
import uz.yukgo.user.BlockService;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;
import java.util.function.Supplier;

/**
 * Har kungi eslatmalar:
 * <ul>
 *   <li>07:00 — haydovchilarga ertalabki xulosa: bugun va ertaga yo'nalishida nechta ochiq e'lon bor. Bugun reysi bor
 *   haydovchiga esa — «Reysga yig'ish»: bitta reysda olib ketadigan yo'lovchilar (bo'sh o'rinlargacha) va 2 tagacha
 *   pochta (rasm bilan, «🧩 Reysni yig'ish» tugmasi);</li>
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
    /** Haydovchilar reyslari shuncha haydovchidan bir so'rovda olinadi */
    static final int TRIP_BATCH = 500;
    /** Bir haydovchiga ertalab ko'pi bilan shuncha «Reysga yig'ish» xabari (bugungi reyslari ko'p bo'lsa) */
    static final int MAX_PLAN_MESSAGES = 2;

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final NotificationService notifications;
    private final TelegramClient telegram;
    private final BlockService blocks;
    private final PlatformTransactionManager txManager;
    private final TripRepository tripRepository;
    private final OrderRules rules;

    /** Bitta haydovchiga ertalabki xulosa: umumiy xabar va (bugun reysi bo'lsa) «Reysga yig'ish» xabarlari */
    record Digest(User driver, int total, Msg msg, List<PlanNote> plans) {
        String html() {
            return msg == null ? null : msg.html();
        }
    }

    /** «Reysga yig'ish» xabari va havolasi (reys sahifasi) */
    public record PlanNote(Msg msg, String link) {}

    /** Sanasi o'tgan e'lon egasiga savol */
    record LatePrompt(User sender, Long chatId, Long orderId, String title, String html) {}

    // =====================================================================
    // 07:00 — ertalabki xulosa
    // =====================================================================

    @Scheduled(cron = "0 0 7 * * *", zone = "Asia/Tashkent")
    public void morningDigest() {
        LocalDate today = LocalDate.now(TZ);
        List<Digest> list = readOnly(() -> digests(today, Instant.now()));
        int sent = 0, planned = 0;
        for (Digest d : list) {
            try {
                if (!d.plans().isEmpty()) {
                    // Bugun reysi bor: umumiy xulosa o'rniga — reysiga yig'ish (unda umumiy son ham bor)
                    for (PlanNote p : d.plans()) {
                        if (notifications.telegramOrPush(d.driver(), p.msg(), p.link())) planned++;
                        if (!pause()) return;
                    }
                } else if (d.msg() != null && notifications.telegramOrPush(d.driver(), d.msg(), "#/")) {
                    sent++;
                }
            } catch (Exception e) {
                log.warn("Ertalabki xabar yuborilmadi (haydovchi {}): {}", d.driver().getId(), TelegramClient.safe(e));
            }
            if (!pause()) break;
        }
        if (sent + planned > 0) log.info("Ertalabki xulosa: {} ta haydovchiga, «Reysga yig'ish»: {} ta xabar", sent, planned);
    }

    /**
     * Har bir haydovchi uchun: uyi yoki ishlaydigan viloyatidan / viloyatiga, turi va mashinasiga mos ochiq e'lonlar.
     * «Faqat reyslarimga mos e'lonlar» yoqqan haydovchiga — faqat faol reyslaridan biriga (yo'nalishi, sanasi va bo'sh
     * joylari bo'yicha, xuddi yangi e'lon xabaridagidek) mos kelganlari; faol reysi bo'lmasa — xulosa yo'q.
     */
    List<Digest> digests(LocalDate today, Instant now) {
        List<Order> open = orderRepository.openForDigest(today, today.plusDays(1), now.minus(NO_DATE_WINDOW));
        if (open.isEmpty()) return List.of();
        List<User> drivers = userRepository.findDriversForDigest();
        LocalDateTime at = LocalDateTime.ofInstant(now, TZ);
        Map<Long, List<Trip>> tripsByDriver = activeTrips(drivers, at);
        int cap = rules.parcelCap();
        List<Digest> out = new ArrayList<>();
        for (User d : drivers) {
            boolean routeOnly = d.isRouteOnly();
            // «Bandman»: umumiy xulosa yo'q, lekin bugun reysi bo'lsa — «Reysga yig'ish» (reysga mos xabarlar doim keladi)
            boolean busy = !d.isAvailable();
            List<Trip> trips = tripsByDriver.getOrDefault(d.getId(), List.of());
            Set<Long> regions = new HashSet<>(d.getWorkRegionIds());
            if (d.getRegion() != null) regions.add(d.getRegion().getId());
            if (routeOnly || busy ? trips.isEmpty() : regions.isEmpty() && trips.isEmpty()) continue;
            Set<Long> hidden = null;
            List<Order> fit = new ArrayList<>();
            for (Order o : busy ? List.<Order>of() : open) {
                if (o.getSender().getId().equals(d.getId())) continue;
                boolean onRoute = routeOnly
                        ? trips.stream().anyMatch(t -> onTrip(t, o, at))
                        : regions.contains(o.getFromRegion().getId()) || regions.contains(o.getToRegion().getId());
                if (!onRoute) continue;
                if (!MatchService.kindAndVehicleFit(d, o)) continue;
                if (hidden == null) hidden = blocks.related(d.getId());
                if (hidden.contains(o.getSender().getId())) continue;
                fit.add(o);
            }
            List<PlanNote> plans = plansForToday(d, trips, open, today, at, now, cap, fit.size());
            if (!fit.isEmpty() || !plans.isEmpty()) out.add(new Digest(d, fit.size(), fit.isEmpty() ? null : digest(d, fit), plans));
        }
        return out;
    }

    /**
     * Bugun jo'naydigan reyslar (yoki «har kuni» reysning bugungi qatnovi) uchun «Reysga yig'ish»: yo'lovchilar bo'sh
     * o'rinlargacha va 2 tagacha pochta. Bo'sh to'plam — xabar yo'q.
     */
    public List<PlanNote> plansForToday(User d, List<Trip> trips, List<Order> open, LocalDate today, LocalDateTime at, Instant now,
                                 int cap, int others) {
        List<PlanNote> out = new ArrayList<>();
        if (trips.isEmpty() || !d.isNotifyNewOrders()) return out;
        List<Order> active = null;
        Set<Long> hidden = null;
        int slots = 0;
        List<Trip> sorted = new ArrayList<>(trips);
        sorted.sort(Comparator.comparing(Trip::getDepartAt));
        for (Trip t : sorted) {
            LocalDateTime when = TripPlanner.departure(t, at);
            if (when == null || !when.toLocalDate().equals(today)) continue;
            if (active == null) {
                active = orderRepository.findByDriverIdAndStatusIn(d.getId(), OrderRules.ACTIVE);
                hidden = blocks.related(d.getId());
                long parcels = active.stream().filter(o -> o.getKind() == OrderKind.PARCEL).count();
                slots = OrderRules.parcelSlots(cap, parcels);
            }
            TripPlanner.Plan p = TripPlanner.plan(t, d, at, now, open, hidden, active, slots);
            if (p == null || p.isEmpty()) continue;
            out.add(new PlanNote(TripNotes.plan(d, p, true, out.isEmpty() ? others : 0), TripNotes.planLink(t)));
            if (out.size() >= MAX_PLAN_MESSAGES) break;
        }
        return out;
    }

    /** Haydovchilarning faol reyslari (haydovchi bo'yicha) — har {@link #TRIP_BATCH} tadan bitta so'rov */
    private Map<Long, List<Trip>> activeTrips(List<User> drivers, LocalDateTime at) {
        List<Long> ids = new ArrayList<>();
        for (User d : drivers) ids.add(d.getId());
        Map<Long, List<Trip>> out = new HashMap<>();
        for (int i = 0; i < ids.size(); i += TRIP_BATCH) {
            List<Long> part = ids.subList(i, Math.min(ids.size(), i + TRIP_BATCH));
            for (Trip t : tripRepository.findActiveForDrivers(part, at.minusHours(3))) {
                out.computeIfAbsent(t.getDriver().getId(), k -> new ArrayList<>()).add(t);
            }
        }
        return out;
    }

    /** Reys e'longa mosmi: viloyatdan viloyatga yo'nalishi bir xil, sanasi va bo'sh joylari to'g'ri keladi */
    static boolean onTrip(Trip t, Order o, LocalDateTime at) {
        return t.getFromRegion().getId().equals(o.getFromRegion().getId())
                && t.getToRegion().getId().equals(o.getToRegion().getId())
                && MatchService.tripFits(t, o, at);
    }

    /** Umumiy ertalabki xulosa: nechta e'lon, turlari va ko'p uchraydigan yo'nalishlar (rasm bilan) */
    static Msg digest(User d, List<Order> fit) {
        Map<OrderKind, Integer> kinds = new EnumMap<>(OrderKind.class);
        Map<String, Integer> routes = new LinkedHashMap<>();
        for (Order o : fit) {
            kinds.merge(o.getKind() == null ? OrderKind.CARGO : o.getKind(), 1, Integer::sum);
            routes.merge(o.getFromRegion().getName() + " → " + o.getToRegion().getName(), 1, Integer::sum);
        }
        List<Map.Entry<String, Integer>> top = new ArrayList<>(routes.entrySet());
        top.sort((a, b) -> b.getValue() - a.getValue());
        String first = d.getFirstName() == null ? "" : d.getFirstName().trim();

        Msg m = Msg.of("☀️ Xayrli tong" + (first.isEmpty() ? "" : ", " + first) + "!");
        m.intro("Bugun va ertaga " + (d.isRouteOnly() ? "reyslaringizga mos" : "yo'nalishlaringizda") + " " + fit.size()
                + " ta ochiq e'lon bor:");
        m.line(null, kindLine(kinds));
        int shown = 0;
        for (Map.Entry<String, Integer> r : top) {
            if (shown++ == TOP_ROUTES) break;
            m.line("📍", r.getKey() + " — " + r.getValue() + " ta");
        }
        int rest = top.size() - TOP_ROUTES;
        if (rest > 0) m.line("…", "va yana " + rest + " ta yo'nalish");
        m.note("Ko'rish va taklif berish uchun tugmani bosing. Bunday xabarlarni Sozlamalarda o'chirib qo'yishingiz mumkin.");

        TgCard c = TgCard.of(TgCard.Theme.MORNING).badge("Xayrli tong")
                .title((first.isEmpty() ? "Xayrli tong!" : "Xayrli tong, " + first + "!") + " Bugun " + fit.size() + " ta e'lon bor");
        Integer p = kinds.get(OrderKind.PASSENGER), q = kinds.get(OrderKind.PARCEL), cg = kinds.get(OrderKind.CARGO);
        if (p != null) c.stat(TgCard.Icon.PEOPLE, String.valueOf(p), "yo'lovchi");
        if (q != null) c.stat(TgCard.Icon.PARCEL, String.valueOf(q), "pochta");
        if (cg != null) c.stat(TgCard.Icon.BOX, String.valueOf(cg), "yuk");
        for (int i = 0; i < Math.min(2, top.size()); i++) {
            Map.Entry<String, Integer> r = top.get(i);
            c.chip(TgCard.Icon.ROUTE, shortRoute(r.getKey()) + " · " + r.getValue());
        }
        return m.button("👀 Ko'rish").card(c.tagline("Kuningiz barakali o'tsin!"));
    }

    /** "Namangan viloyati → Toshkent shahri" → "Namangan → Toshkent" (rasmga sig'sin) */
    static String shortRoute(String route) {
        return route.replace(" viloyati", "").replace(" shahri", "").replace(" Respublikasi", "");
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
            Msg m = Msg.of("📅 E'loningiz sanasi o'tdi").intro("Hali hech kim olmadi.");
            OrderNotes.basics(m, o, o.getPrice());
            m.para("Hali kerakmi? Bugunga ko'chirsangiz, e'lon yana yangilar qatorida turadi va shu yo'nalishdagi "
                    + "haydovchilarga qayta xabar boradi.");
            String html = m.html();
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
