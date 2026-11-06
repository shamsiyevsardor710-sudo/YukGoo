package uz.yukgo.order;

import uz.yukgo.channel.OrderCard;
import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.notification.Msg;
import uz.yukgo.notification.TgCard;
import uz.yukgo.user.User;

import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Buyurtma haqidagi bildirishnomalar matni va rasmi (Telegramda rasm + tartibli matn, saytda va push'da — qator-qator).
 * Bir joyda — hamma xabar bir xil uslubda bo'lsin: 📍 yo'nalish, 📅 sana, 🧍 kishilar, 💰 narx, 👤 kim, 📞 telefon.
 */
public final class OrderNotes {
    private OrderNotes() {}

    static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    static final String[] MONTHS = {"yanvar", "fevral", "mart", "aprel", "may", "iyun", "iyul", "avgust", "sentabr",
            "oktabr", "noyabr", "dekabr"};

    // =====================================================================
    // Bo'laklar
    // =====================================================================

    /** "8-noyabr" (boshqa yil bo'lsa — "8-noyabr 2027") */
    public static String date(LocalDate d) {
        String s = d.getDayOfMonth() + "-" + MONTHS[d.getMonthValue() - 1];
        return d.getYear() == LocalDate.now(TZ).getYear() ? s : s + " " + d.getYear();
    }

    /** "Bugun, 6-noyabr", "Ertaga, 7-noyabr", "Indinga, 8-noyabr" yoki "12-noyabr" */
    public static String day(LocalDate d) {
        LocalDate today = LocalDate.now(TZ);
        String label = d.equals(today) ? "Bugun" : d.equals(today.plusDays(1)) ? "Ertaga"
                : d.equals(today.plusDays(2)) ? "Indinga" : null;
        return label == null ? date(d) : label + ", " + date(d);
    }

    /** "Namangan viloyati" → "Namangan", "Toshkent shahri" → "Toshkent" */
    public static String shortRegion(Region r) {
        if (r == null || r.getName() == null) return "";
        return r.getName().replace(" viloyati", "").replace(" shahri", "").replace(" Respublikasi", "").trim();
    }

    /** "Kosonsoy (Namangan)" yoki tumansiz — "Namangan viloyati" */
    public static String place(Region r, District d) {
        if (r == null) return "—";
        return d == null ? r.getName() : d.getName() + " (" + shortRegion(r) + ")";
    }

    /** "Kosonsoy (Namangan) → Chilonzor (Toshkent)" */
    public static String route(Order o) {
        return place(o.getFromRegion(), o.getFromDistrict()) + " → " + place(o.getToRegion(), o.getToDistrict());
    }

    /** Rasm uchun: tuman katta, viloyat kichik (tuman bo'lmasa — viloyat katta) */
    public static String main(Region r, District d) {
        return d != null ? d.getName() : r == null ? "—" : r.getName();
    }

    public static String sub(Region r, District d) {
        return d != null && r != null ? r.getName() : null;
    }

    public static String money(Long v) {
        return v == null || v <= 0 ? "Narx kelishiladi" : OrderCard.money(v);
    }

    static int seats(Order o) {
        return o.getSeats() == null || o.getSeats() < 1 ? 1 : o.getSeats();
    }

    /** Pochtaning foydali nomi ("Hujjatlar"), umumiy "Pochta" bo'lsa — null */
    static String parcelName(Order o) {
        String t = OrderCard.publicTitle(o.getTitle());
        return t.equals("Yuk") || t.equalsIgnoreCase("Pochta") ? null : t;
    }

    /** "🧍 2 kishi", "✉️ Pochta: hujjatlar", "📦 20 qop sement" — Msg qatori */
    public static Msg what(Msg m, Order o) {
        switch (o.getKind()) {
            case PASSENGER -> m.strong("🧍", seats(o) + " kishi");
            case PARCEL -> {
                String n = parcelName(o);
                m.strong("✉️", n == null ? "Pochta" : "Pochta: " + n);
            }
            default -> {
                String w = o.getWeightKg() != null ? " · " + OrderCard.weight(o.getWeightKg()) : "";
                m.strong("📦", OrderCard.publicTitle(o.getTitle()) + w);
            }
        }
        return m;
    }

    /** Asosiy qatorlar: 📍 yo'nalish, 📅 sana, nima, 💰 narx */
    public static Msg basics(Msg m, Order o, Long price) {
        m.strong("📍", route(o));
        if (o.getPickupDate() != null) m.line("📅", day(o.getPickupDate()));
        what(m, o);
        return m.kv("💰", "Narx", money(price));
    }

    public static TgCard.Theme theme(Order o) {
        return switch (o.getKind()) {
            case PASSENGER -> TgCard.Theme.PASSENGER;
            case PARCEL -> TgCard.Theme.PARCEL;
            default -> TgCard.Theme.CARGO;
        };
    }

    /** Karta: yo'nalish va sana, kishilar (yoki pochta nomi, yuk og'irligi), narx */
    public static TgCard card(Order o, TgCard.Theme theme, String badge, String title, Long price, String tagline) {
        TgCard c = TgCard.of(theme).badge(badge).title(title)
                .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                        main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()));
        if (o.getPickupDate() != null) c.chip(TgCard.Icon.CALENDAR, day(o.getPickupDate()));
        switch (o.getKind()) {
            case PASSENGER -> c.chip(TgCard.Icon.PEOPLE, seats(o) + " kishi");
            case PARCEL -> c.chip(TgCard.Icon.PARCEL, parcelName(o) == null ? "Pochta" : parcelName(o));
            default -> {
                if (o.getWeightKg() != null) c.chip(TgCard.Icon.BOX, OrderCard.weight(o.getWeightKg()));
            }
        }
        c.chip(TgCard.Icon.MONEY, money(price));
        return c.tagline(tagline);
    }

    /** Yangi e'lon kartasi (haydovchilarga): bitta e'lon — bitta rasm, hammaga shu */
    public static TgCard newOrderCard(Order o) {
        String title = switch (o.getKind()) {
            case PASSENGER -> "Yangi yo'lovchi!";
            case PARCEL -> "Yangi pochta!";
            default -> OrderCard.publicTitle(o.getTitle());
        };
        return card(o, theme(o), o.isUrgent() ? "Shoshilinch" : o.getKind().label(), title, o.getPrice(),
                "Birinchi bo'lib bog'laning!");
    }

    // =====================================================================
    // Buyurtma hayoti
    // =====================================================================

    /** Haydovchiga: buyurtma unga berildi (o'zi oldi yoki yuboruvchi taklifini tanladi) */
    static Msg assignedToDriver(Order o, Long agreed, long fee) {
        boolean passenger = o.isPassenger();
        User s = o.getSender();
        Msg m = Msg.of(passenger ? "✅ Yo'lovchi sizniki!" : "✅ Buyurtma sizga berildi!");
        basics(m, o, agreed);
        m.kv("👤", passenger ? "Yo'lovchi" : "Yuboruvchi", s.getFullName());
        m.kv("📞", "Telefon", s.getPhone());
        if (fee > 0) m.line("💳", "Balansingizdan " + OrderCard.money(fee) + " komissiya yechildi");
        if (agreed == null) m.note("Narxni telefonda kelishib oling");
        String title = passenger ? "Yo'lovchi sizniki!" : o.getKind() == OrderKind.PARCEL ? "Pochta sizniki!" : "Buyurtma sizniki!";
        return m.card(card(o, TgCard.Theme.SUCCESS, "Tabriklaymiz", title, agreed, "Oq yo'l! Omad yor bo'lsin").confetti());
    }

    /** Yuboruvchiga: haydovchi e'lonni o'zi oldi («Olaman») */
    static Msg driverFound(Order o, User driver, Long agreed) {
        boolean passenger = o.isPassenger();
        Msg m = Msg.of(passenger ? "🚗 Haydovchi topildi!" : "Haydovchi buyurtmangizni oldi");
        m.strong("📍", route(o));
        if (o.getPickupDate() != null) m.line("📅", day(o.getPickupDate()));
        m.kv("👤", "Haydovchi", driver.getFullName() + (driver.getRatingCount() > 0 ? " (⭐ " + driver.getRatingAvg() + ")" : ""));
        m.kv("📞", "Telefon", driver.getPhone());
        String car = car(driver);
        if (car != null) m.kv("🚗", "Mashina", car);
        m.kv("💰", "Narx", money(agreed));
        if (agreed == null) m.note("Narxni telefonda kelishib oling");
        else if (passenger) m.note("Haydovchi siz bilan bog'lanadi — tayyor turing");
        TgCard c = TgCard.of(TgCard.Theme.SUCCESS).badge("Haydovchi topildi")
                .title(passenger ? "Sizni olib ketishadi!" : "Haydovchi topildi!")
                .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                        main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()));
        if (car != null) c.chip(TgCard.Icon.CAR, car);
        if (driver.getRatingCount() > 0) c.chip(TgCard.Icon.STAR, String.valueOf(driver.getRatingAvg()));
        c.tagline(passenger ? "Oq yo'l! Safaringiz bexatar bo'lsin" : "Yukingiz ishonchli qo'llarda").confetti();
        return m.card(c);
    }

    /** "Chevrolet Cobalt · 01 A 123 BC" */
    static String car(User d) {
        if (d.getVehicle() == null || !d.getVehicle().isPresent()) return null;
        var v = d.getVehicle();
        String name = v.getBrand() + (v.getModel() != null && !v.getModel().isBlank() ? " " + v.getModel() : "");
        return v.getPlateNumber() != null ? name + " · " + v.getPlateNumber() : name;
    }

    /** Yuboruvchiga: yo'lovchi olindi / pochta yoki yuk yo'lda */
    static Msg pickedUp(Order o, User driver) {
        if (o.isPassenger()) {
            Msg m = Msg.of("🚗 Oq yo'l!").strong("📍", route(o))
                    .para("Safaringiz bexatar bo'lsin. Manzilga yetgach haydovchini baholashni unutmang.");
            TgCard c = TgCard.of(TgCard.Theme.TRIP).badge("Yo'ldamiz").title("Oq yo'l!")
                    .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                            main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()));
            String car = car(driver);
            if (car != null) c.chip(TgCard.Icon.CAR, car);
            return m.card(c.tagline("Safaringiz bexatar bo'lsin!"));
        }
        boolean parcel = o.getKind() == OrderKind.PARCEL;
        Msg m = Msg.of(parcel ? "✉️ Pochtangiz yo'lda" : "🚚 Yukingiz yo'lda").strong("📍", route(o));
        what(m, o);
        m.kv("👤", "Haydovchi", driver.getFullName()).kv("📞", "Telefon", driver.getPhone())
                .kv("🔑", "Topshirish kodi", o.getDeliveryCode())
                .note("Xaritada kuzatishingiz mumkin. Qabul qiluvchiga topshirish kodini ayting");
        TgCard c = TgCard.of(TgCard.Theme.TRIP).badge("Yo'lda").title(parcel ? "Pochtangiz yo'lda" : "Yukingiz yo'lda")
                .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                        main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()));
        String car = car(driver);
        if (car != null) c.chip(TgCard.Icon.CAR, car);
        return m.card(c.tagline("Xaritada kuzatib boring"));
    }

    /** Yuboruvchiga: yetib keldi / topshirildi */
    static Msg deliveredToSender(Order o) {
        boolean passenger = o.isPassenger();
        boolean parcel = o.getKind() == OrderKind.PARCEL;
        Msg m = Msg.of(passenger ? "🎉 Manzilga yetdingiz!" : parcel ? "🎉 Pochta yetkazildi!" : "🎉 Yuk yetkazildi!")
                .strong("📍", route(o));
        if (!passenger) what(m, o);
        m.para(passenger ? "Safar yakunlandi. Iltimos, haydovchini baholang — bu boshqalarga yordam beradi."
                : "Qabul qiluvchiga topshirildi. Iltimos, haydovchini baholang — bu boshqalarga yordam beradi.");
        TgCard c = TgCard.of(TgCard.Theme.SUCCESS).badge("Yakunlandi")
                .title(passenger ? "Manzilga yetdingiz!" : parcel ? "Pochta yetkazildi!" : "Yuk yetkazildi!")
                .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                        main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()))
                .chip(TgCard.Icon.STAR, "Haydovchini baholang").tagline("YukGo bilan — rahmat!").confetti();
        return m.button("⭐ Baholash").card(c);
    }

    /** Haydovchiga: safar yoki buyurtma yakunlandi */
    static Msg deliveredToDriver(Order o) {
        boolean passenger = o.isPassenger();
        Msg m = Msg.of(passenger ? "Safar yakunlandi" : "Buyurtma yakunlandi").strong("📍", route(o));
        what(m, o);
        Long price = o.getFinalPrice() != null ? o.getFinalPrice() : o.getPrice();
        if (price != null && price > 0) m.kv("💰", "Narx", OrderCard.money(price));
        m.para("Rahmat! " + (passenger ? "Yo'lovchini" : "Yuboruvchini") + " baholashni unutmang.");
        TgCard c = TgCard.of(TgCard.Theme.SUCCESS).badge("Barakalla").title(passenger ? "Safar yakunlandi!" : "Buyurtma yakunlandi!")
                .route(main(o.getFromRegion(), o.getFromDistrict()), sub(o.getFromRegion(), o.getFromDistrict()),
                        main(o.getToRegion(), o.getToDistrict()), sub(o.getToRegion(), o.getToDistrict()));
        if (price != null && price > 0) c.chip(TgCard.Icon.MONEY, OrderCard.money(price));
        c.chip(TgCard.Icon.STAR, "Baho qoldiring");
        return m.button("⭐ Baholash").card(c.tagline("Yana yangi buyurtmalar kutmoqda!").confetti());
    }

    /** Yangi taklif (yuboruvchiga) */
    static Msg newOffer(Order o, User driver, long price, boolean isNew) {
        Msg m = Msg.of((isNew ? "💬 Yangi taklif: " : "💬 Taklif yangilandi: ") + OrderCard.money(price));
        m.strong("📍", route(o));
        what(m, o);
        m.kv("👤", "Haydovchi", driver.getFullName() + " (⭐ " + driver.getRatingAvg() + ", "
                + driver.getDeliveriesCompleted() + " ta yetkazish)");
        String car = car(driver);
        if (car != null) m.kv("🚗", "Mashina", car);
        return m.note("Takliflarni solishtirib, ma'qulini tanlang").button("Takliflarni ko'rish");
    }

    // =====================================================================
    // Bekor qilish, voz kechish, rad etish
    // =====================================================================

    /** 📍 yo'nalish, 📅 sana va nima — qisqa (narxsiz) */
    static Msg brief(Msg m, Order o) {
        m.strong("📍", route(o));
        if (o.getPickupDate() != null) m.line("📅", day(o.getPickupDate()));
        return what(m, o);
    }

    /** Haydovchiga: e'lon egasi taklifini rad etdi */
    static Msg offerRejected(Order o) {
        return brief(Msg.of("Taklifingiz qabul qilinmadi"), o)
                .note("Boshqa e'lonlar ham bor — «E'lonlar» bo'limida yangilarini ko'ring");
    }

    /** Taklif bergan boshqa haydovchilarga: e'lon boshqasiga berildi */
    static Msg givenToOther(Order o) {
        return brief(Msg.of("E'lon boshqa haydovchiga berildi"), o)
                .note("Bu safar boshqa taklif tanlandi. Omad — yangi e'lonlar kutmoqda!");
    }

    /** Haydovchiga: yuboruvchi (yo'lovchi) bekor qildi */
    static Msg cancelledBySender(Order o, String reason) {
        boolean passenger = o.isPassenger();
        return brief(Msg.of(passenger ? "❌ Yo'lovchi safarni bekor qildi" : "❌ Buyurtma bekor qilindi"), o)
                .kv("💬", "Sabab", reason)
                .note(passenger ? "Bo'sh o'rinlaringizga boshqa yo'lovchilar ham bor — «Reysga yig'ish»da qarang"
                        : "Yuboruvchi buyurtmani bekor qildi");
    }

    /** Yuboruvchiga: haydovchi voz kechdi, e'lon qayta ochildi */
    static Msg driverWithdrew(Order o, User driver, String reason) {
        return brief(Msg.of("😕 Haydovchi voz kechdi"), o)
                .kv("👤", "Haydovchi", driver.getFullName())
                .kv("💬", "Sabab", reason)
                .para("E'loningiz qayta e'lon qilindi — shu yo'nalishdagi haydovchilarga yana xabar ketdi.");
    }

    /** Ikki tomonga: admin bekor qildi */
    static Msg cancelledByAdmin(Order o, String reason) {
        return brief(Msg.of("❌ Buyurtma bekor qilindi"), o)
                .kv("💬", "Sabab", reason)
                .note("Admin tomonidan bekor qilindi. Savol bo'lsa — Profil → «Biz bilan bog'lanish»");
    }

    /** Haydovchiga: bekor qilingan buyurtma komissiyasi qaytdi */
    static Msg commissionRefunded(Order o, long fee) {
        return Msg.of("💳 Komissiya qaytarildi")
                .strong("➕", "+" + OrderCard.money(fee))
                .line("📍", route(o))
                .note("Summa balansingizga qaytarildi");
    }

    /** Yuboruvchiga: olib ketish sanasi o'tgan e'lon avtomatik yopildi */
    static Msg autoClosed(Order o) {
        return brief(Msg.of("📅 E'loningiz yopildi"), o)
                .para("Olib ketish sanasi o'tgani uchun e'lon avtomatik yopildi. Hali kerak bo'lsa — e'lon sahifasida "
                        + "«🔁 Qayta joylash» bilan yangi sana qo'ying.")
                .button("🔁 Qayta joylash");
    }
}
