package uz.yukgo.trip;

import uz.yukgo.notification.Msg;
import uz.yukgo.notification.TgCard;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderNotes;
import uz.yukgo.user.User;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Reys va «Reysga yig'ish» haqidagi bildirishnomalar matni va rasmi: haydovchiga mos e'lon, yo'lovchiga «haydovchi bor»,
 * ertalab «bugungi reysingizga yig'ib oling».
 */
public final class TripNotes {
    private TripNotes() {}

    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter HHMM = DateTimeFormatter.ofPattern("HH:mm");

    /** «Bugun, 07:00», «Ertaga, 07:00», «8-noyabr, 07:00» */
    public static String departure(LocalDateTime at) {
        LocalDate d = at.toLocalDate(), today = LocalDate.now(TZ);
        String day = d.equals(today) ? "Bugun" : d.equals(today.plusDays(1)) ? "Ertaga"
                : d.equals(today.plusDays(2)) ? "Indinga" : OrderNotes.date(d);
        return day + ", " + at.format(HHMM);
    }

    /** «Har kuni 07:00» yoki «Ertaga, 07:00» */
    public static String when(Trip t) {
        return t.isDaily() ? "Har kuni " + t.getDepartAt().format(HHMM) : departure(t.getDepartAt());
    }

    /** «Kosonsoy (Namangan) → Toshkent shahri» */
    public static String route(Trip t) {
        return OrderNotes.place(t.getFromRegion(), t.getFromDistrict()) + " → " + OrderNotes.place(t.getToRegion(), t.getToDistrict());
    }

    /** «3 yo'lovchi (4 kishi) va 2 ta pochta» */
    public static String bundle(TripPlanner.Plan p) {
        StringBuilder sb = new StringBuilder();
        int n = p.passengers().size();
        if (n > 0) {
            sb.append(n).append(" yo'lovchi");
            if (p.seatsFilled() != n) sb.append(" (").append(p.seatsFilled()).append(" kishi)");
        }
        if (!p.parcels().isEmpty()) {
            if (!sb.isEmpty()) sb.append(" va ");
            sb.append(p.parcels().size()).append(" ta pochta");
        }
        return sb.toString();
    }

    static boolean contains(TripPlanner.Plan p, Order o) {
        if (p == null || o.getId() == null) return false;
        for (Order x : p.passengers()) if (o.getId().equals(x.getId())) return true;
        for (Order x : p.parcels()) if (o.getId().equals(x.getId())) return true;
        return false;
    }

    public static String planLink(Trip t) {
        return "#/trips/" + t.getId() + "/plan";
    }

    // =====================================================================

    /** Haydovchiga: reysiga mos yangi e'lon (va reysga yana nimalar sig'ishi) */
    static Msg tripMatch(Order o, Trip t, TripPlanner.Plan plan) {
        Msg m = Msg.of(MatchService.tripMatchTitle(o));
        OrderNotes.basics(m, o, o.getPrice());
        m.kv("🚗", "Reysingiz", when(t));
        if (!MatchService.sameFromDistrict(t, o)) m.note("Reysingiz boshqa tumandan — e'lon yo'lingizda");
        if (plan != null && plan.items() >= 2 && contains(plan, o)) {
            m.para("💡 Reysingizga endi " + bundle(plan) + " mos — hammasini bitta reysda olib ketishingiz mumkin.");
            m.extra("🧩 Reysni yig'ish", planLink(t));
        }
        return m.button("👀 Ko'rish").card(OrderNotes.newOrderCard(o));
    }

    /** Hudud bo'yicha haydovchiga: yangi e'lon (Telegram yoki push, saytda 🔔 emas) */
    static Msg newOrder(Order o, boolean near) {
        Msg m = Msg.of(near ? "📍 Tumaningizdan yangi e'lon" : "🆕 Yangi e'lon");
        OrderNotes.basics(m, o, o.getPrice());
        if (o.isUrgent()) m.note("🔥 Shoshilinch — tez javob bering");
        return m.button("👀 Ko'rish").card(OrderNotes.newOrderCard(o));
    }

    /** E'lon egasiga: yo'nalishida yangi reys e'lon qilindi */
    static Msg driverOnRoute(User d, Trip t) {
        Msg m = Msg.of("🚗 Yo'nalishingizda haydovchi bor");
        m.strong("📍", route(t));
        m.line("🕖", when(t));
        if (t.getSeats() != null) m.line("💺", t.getSeats() + " ta bo'sh o'rin");
        if (t.getPriceNote() != null) m.kv("💰", "Narx", t.getPriceNote());
        m.kv("👤", "Haydovchi", d.getFullName() + (d.getRatingCount() > 0 ? " (⭐ " + d.getRatingAvg() + ")" : ""));
        String car = car(d);
        if (car != null) m.kv("🚗", "Mashina", car);
        m.kv("📞", "Telefon", d.getPhone());
        m.note("Qo'ng'iroq qilib kelishib oling");
        TgCard c = TgCard.of(TgCard.Theme.TRIP).badge("Haydovchi bor").title("Yo'nalishingizda haydovchi bor!")
                .route(OrderNotes.main(t.getFromRegion(), t.getFromDistrict()), OrderNotes.sub(t.getFromRegion(), t.getFromDistrict()),
                        OrderNotes.main(t.getToRegion(), t.getToDistrict()), OrderNotes.sub(t.getToRegion(), t.getToDistrict()))
                .chip(TgCard.Icon.CLOCK, when(t));
        if (t.getSeats() != null) c.chip(TgCard.Icon.PEOPLE, t.getSeats() + " bo'sh o'rin");
        if (car != null) c.chip(TgCard.Icon.CAR, car);
        return m.card(c.tagline("Qo'ng'iroq qiling — kelishib oling!"));
    }

    /** "Chevrolet Cobalt · 01 A 123 BC" */
    static String car(User d) {
        if (d.getVehicle() == null || !d.getVehicle().isPresent()) return null;
        var v = d.getVehicle();
        String name = v.getBrand() + (v.getModel() != null && !v.getModel().isBlank() ? " " + v.getModel() : "");
        return v.getPlateNumber() != null ? name + " · " + v.getPlateNumber() : name;
    }

    /**
     * Haydovchiga: reysiga bitta reysda olib ketiladigan to'plam (ertalabki xabar). others — reyslardan tashqari,
     * yo'nalishlarida yana nechta e'lon bor (ertalabki xulosadan).
     */
    public static Msg plan(User d, TripPlanner.Plan p, boolean morning, int others) {
        Trip t = p.trip();
        String first = d.getFirstName() == null ? "" : d.getFirstName().trim();
        String title = morning ? "☀️ Xayrli tong" + (first.isEmpty() ? "" : ", " + first) + "!" : "🧩 Reysingizga yig'ib oling";
        Msg m = Msg.of(title);
        m.intro(p.day().equals(LocalDate.now(TZ)) ? "Bugungi reysingizga mos e'lonlar bor 👇" : "Reysingizga mos e'lonlar bor 👇");
        m.strong("🚗", route(t) + " · " + departure(p.departAt()));
        if (!p.passengers().isEmpty() || p.seatsInCar() > 0) m.line("💺", seatsLine(p));
        if (!p.parcels().isEmpty()) m.line("✉️", p.parcels().size() + " ta pochta mos");
        int more = p.morePassengers() + p.moreParcels();
        if (more > 0) m.line("➕", "Yo'nalishda yana " + more + " ta e'lon bor");
        if (others > 0) m.line("🗂", "Hududlaringizda jami " + others + " ta ochiq e'lon");
        m.note("Hammasini bitta reysda olib ketishingiz mumkin. «Olaman» bosing — e'lonlar tez band bo'ladi!");
        return m.button("🧩 Reysni yig'ish").card(planCard(p, morning ? "Bugungi reys" : "Reysingiz"));
    }

    /** «4 o'rin: 3 yo'lovchi (4 kishi) mos» yoki «Mashinangizda 1 kishi, yana 2 yo'lovchi mos — 1 o'rin bo'sh» */
    static String seatsLine(TripPlanner.Plan p) {
        StringBuilder sb = new StringBuilder();
        sb.append(p.capacity()).append(" o'rin");
        if (p.seatsInCar() > 0) sb.append(", mashinangizda ").append(p.seatsInCar()).append(" kishi");
        if (!p.passengers().isEmpty()) {
            sb.append(": ").append(p.passengers().size()).append(" yo'lovchi");
            if (p.seatsFilled() != p.passengers().size()) sb.append(" (").append(p.seatsFilled()).append(" kishi)");
            sb.append(" mos");
        }
        sb.append(p.freeSeats() == 0 ? " — mashina to'ladi!" : " — " + p.freeSeats() + " o'rin bo'sh qoladi");
        return sb.toString();
    }

    static TgCard planCard(TripPlanner.Plan p, String badge) {
        Trip t = p.trip();
        TgCard c = TgCard.of(TgCard.Theme.TRIP).badge(badge).title("Reysingizga " + p.items() + " ta e'lon mos!")
                .route(OrderNotes.main(t.getFromRegion(), t.getFromDistrict()), OrderNotes.sub(t.getFromRegion(), t.getFromDistrict()),
                        OrderNotes.main(t.getToRegion(), t.getToDistrict()), OrderNotes.sub(t.getToRegion(), t.getToDistrict()));
        if (!p.passengers().isEmpty() || p.seatsInCar() > 0) c.seats(p.capacity(), p.seatsInCar(), p.seatsFilled());
        c.chip(TgCard.Icon.CLOCK, departure(p.departAt()));
        if (!p.passengers().isEmpty()) c.chip(TgCard.Icon.PEOPLE, p.passengers().size() + " yo'lovchi");
        if (!p.parcels().isEmpty()) c.chip(TgCard.Icon.PARCEL, p.parcels().size() + " pochta");
        return c.tagline("Bitta reysda — ko'proq daromad!");
    }
}
