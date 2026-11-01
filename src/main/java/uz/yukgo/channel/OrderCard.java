package uz.yukgo.channel;

import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.order.CargoType;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.user.BodyType;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import static uz.yukgo.telegram.TelegramClient.esc;

/**
 * Buyurtmaning Telegram uchun qisqa kartasi (HTML): kanal postlari va botdagi "buyurtmani ochish" javobi uchun.
 * Yuk egasining ismi va telefoni hech qachon chiqmaydi — ular faqat YukGo ichida, kelishgandan keyin ko'rinadi.
 */
public final class OrderCard {
    private static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final Map<BodyType, String> BODY = new EnumMap<>(BodyType.class);
    private static final Map<CargoType, String> CARGO = new EnumMap<>(CargoType.class);

    static {
        // Faqat hozirgi 6 tur; eski qiymatlar (Tentli, Refrijerator...) canonical() orqali shulardan biriga tushadi
        BODY.put(BodyType.TRUCK, "Yuk mashinasi");
        BODY.put(BodyType.SEDAN, "Yengil mashina");
        BODY.put(BodyType.BORT, "Ochiq bortli");
        BODY.put(BodyType.FURGON, "Furgon");
        BODY.put(BodyType.CONTAINER, "Konteyner tashuvchi");
        BODY.put(BodyType.OTHER, "Boshqa");
        CARGO.put(CargoType.GENERAL, "Umumiy yuk");
        CARGO.put(CargoType.PARCEL, "Posilka");
        CARGO.put(CargoType.DOCUMENTS, "Hujjatlar");
        CARGO.put(CargoType.FOOD, "Oziq-ovqat");
        CARGO.put(CargoType.PRODUCE, "Meva-sabzavot");
        CARGO.put(CargoType.FURNITURE, "Mebel");
        CARGO.put(CargoType.HOUSEHOLD, "Uy ko'chirish");
        CARGO.put(CargoType.APPLIANCES, "Maishiy texnika");
        CARGO.put(CargoType.BUILDING_MATERIALS, "Qurilish materiallari");
        CARGO.put(CargoType.EQUIPMENT, "Uskuna va texnika");
        CARGO.put(CargoType.FRAGILE, "Mo'rt yuk");
        CARGO.put(CargoType.LIVESTOCK, "Chorva");
        CARGO.put(CargoType.OTHER, "Boshqa");
    }

    public static String bodyLabel(BodyType b) {
        return b == null ? null : BODY.getOrDefault(b.canonical(), "Boshqa");
    }

    public static String cargoLabel(CargoType c) {
        return c == null ? null : CARGO.getOrDefault(c, "Boshqa");
    }

    private OrderCard() {}

    /** Telefon raqamlar: +998 90 123 45 67, 901234567, 90-123-45-67 */
    private static final Pattern PHONE = Pattern.compile("(\\+?998[\\s\\-()]*\\d{2}[\\s\\-()]*\\d{3}[\\s\\-]*\\d{2}[\\s\\-]*\\d{2})"
            + "|(\\b\\d{2}[\\s\\-]?\\d{3}[\\s\\-]?\\d{2}[\\s\\-]?\\d{2}\\b)");
    /** Havolalar va Telegram username'lari */
    private static final Pattern LINK = Pattern.compile("(?i)(?:https?://|www\\.|t\\.me/|telegram\\.me/)\\S+|@[A-Za-z0-9_]{4,32}");

    /**
     * Ochiq kanal va bot kartasidagi sarlavha: foydalanuvchi sarlavhaga yozib qo'ygan telefon raqam, havola va @username
     * olib tashlanadi — bog'lanish faqat YukGo orqali (kelishgandan keyin).
     */
    public static String publicTitle(String title) {
        if (title == null) return "Yuk";
        String t = LINK.matcher(PHONE.matcher(title).replaceAll(" ")).replaceAll(" ")
                .replaceAll("[ \\t]{2,}", " ").replaceAll("[\\s,;:.\\-–—]+$", "").trim();
        return t.length() < 2 ? "Yuk" : t;
    }

    /** OPEN — haydovchi kutilmoqda, TAKEN — haydovchi topildi (yo'lda yoki yetkazilgan), CLOSED — bekor qilingan */
    public static String state(Order o) {
        if (o.getStatus() == OrderStatus.NEW) return "OPEN";
        if (o.getStatus() == OrderStatus.CANCELLED) return "CLOSED";
        return "TAKEN";
    }

    /** Birinchi qator: «📦 20 qop sement», «✉️ Pochta: hujjatlar», «🧍 Yo'lovchi: 2 kishi» (HTML) */
    public static String headline(Order o) {
        return switch (o.getKind()) {
            case PASSENGER -> "🧍 <b>Yo'lovchi: " + (o.getSeats() == null ? 1 : o.getSeats()) + " kishi</b>";
            case PARCEL -> {
                String t = publicTitle(o.getTitle());
                yield "✉️ <b>Pochta</b>" + (t.equals("Yuk") || t.equalsIgnoreCase("Pochta") ? "" : ": " + esc(t));
            }
            default -> "📦 <b>" + esc(publicTitle(o.getTitle())) + "</b>";
        };
    }

    /** Asosiy matn: e'lon turi va nomi, yo'nalish, sana, og'irlik va kuzov, masofa, narx */
    public static String text(Order o) {
        StringBuilder sb = new StringBuilder();
        if (o.isUrgent()) sb.append("🔥 <b>SHOSHILINCH</b>\n");
        sb.append(headline(o)).append('\n');
        sb.append("📍 ").append(esc(place(o.getFromRegion(), o.getFromDistrict()))).append(" → ")
                .append(esc(place(o.getToRegion(), o.getToDistrict()))).append('\n');
        if (o.getPickupDate() != null) sb.append("📅 ").append(dateLabel(o.getPickupDate())).append('\n');
        List<String> parts = new ArrayList<>();
        if (o.getWeightKg() != null && !o.isPassenger()) parts.add("⚖️ " + weight(o.getWeightKg()));
        if (o.getRequiredBodyType() != null && !o.isPassenger()) parts.add("🚚 " + bodyLabel(o.getRequiredBodyType()));
        if (!parts.isEmpty()) sb.append(String.join(" · ", parts)).append('\n');
        if (o.getDistanceKm() != null) sb.append("🛣 ").append(Math.round(o.getDistanceKm())).append(" km\n");
        sb.append("💰 ").append(o.getPrice() != null ? money(o.getPrice()) : "Narx kelishiladi");
        return sb.toString();
    }

    /** Kanal posti: holatga qarab sarlavha, karta va yo'nalish xeshteglari */
    public static String channelPost(Order o, String state) {
        String head = switch (state) {
            case "TAKEN" -> "✅ <b>HAYDOVCHI TOPILDI</b>\n\n";
            case "CLOSED" -> "❌ <b>E'lon yopilgan</b>\n\n";
            default -> "";
        };
        String tail = !"OPEN".equals(state) ? "" : o.getKind() == uz.yukgo.order.OrderKind.CARGO
                ? "\n\n👇 Taklif berish uchun tugmani bosing" : "\n\n👇 Olib ketish uchun tugmani bosing";
        return head + text(o) + "\n\n" + hashtags(o) + tail;
    }

    /** "#Toshkent #Samarqand" — kanalda yo'nalish bo'yicha qidirish uchun */
    public static String hashtags(Order o) {
        String a = tag(o.getFromRegion()), b = tag(o.getToRegion());
        return a.equals(b) ? a : a + " " + b;
    }

    static String tag(Region r) {
        if (r == null || r.getName() == null) return "#YukGo";
        String n = r.getName().replace(" shahri", "").replace(" viloyati", "").replace(" Respublikasi", "")
                .replaceAll("['‘’ʻʼ`]", "").replaceAll("[^\\p{L}\\p{Nd}]+", "_");
        return "#" + n;
    }

    public static String place(Region r, District d) {
        if (r == null) return "—";
        return d == null ? r.getName() : r.getName() + ", " + d.getName();
    }

    public static String dateLabel(LocalDate d) {
        LocalDate today = LocalDate.now(TZ);
        if (d.equals(today)) return "Bugun";
        if (d.equals(today.plusDays(1))) return "Ertaga";
        if (d.equals(today.plusDays(2))) return "Indinga";
        return d.format(DATE);
    }

    public static String weight(double kg) {
        if (kg >= 1000) {
            double t = kg / 1000.0;
            return (t == Math.floor(t) ? String.valueOf((long) t) : String.format(Locale.ROOT, "%.1f", t)) + " t";
        }
        return (kg == Math.floor(kg) ? String.valueOf((long) kg) : String.valueOf(kg)) + " kg";
    }

    public static String money(long v) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(v).replace('\u00A0', ' ').replace('\u202F', ' ') + " so'm";
    }
}
