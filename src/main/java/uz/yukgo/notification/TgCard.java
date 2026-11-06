package uz.yukgo.notification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

/**
 * Telegram xabaridagi rasm (karta): yuqorida YukGo belgisi va tur yorlig'i, katta sarlavha, yo'nalish
 * (qayerdan → qayerga), belgili «tabletkalar» (sana, kishilar, narx...), kerak bo'lsa o'rinlar va bayram konfettisi.
 * Rasmni {@link CardRenderer} chizadi. Bir xil karta (masalan, bitta e'lon ko'p haydovchiga) bir marta chiziladi va
 * Telegramga bir marta yuklanadi — {@link #key()} bo'yicha.
 */
public final class TgCard {
    /** Rang: yo'lovchi — ko'k, pochta — binafsha, yuk — to'q sariq, reys — sariq, tabrik — yashil, ertalab — quyosh */
    public enum Theme { PASSENGER, PARCEL, CARGO, TRIP, SUCCESS, MORNING }

    public enum Icon { CALENDAR, CLOCK, PEOPLE, PARCEL, BOX, MONEY, CAR, STAR, ROUTE, CHECK, PHONE }

    public record Chip(Icon icon, String text) {}

    /** Katta raqamli kataklar (ertalabki xulosa): «7 — yo'lovchi», «3 — pochta» */
    public record Stat(Icon icon, String value, String label) {}

    /** O'rinlar: jami, allaqachon olingan (mashinada), tavsiya etilgan */
    public record Seats(int capacity, int taken, int suggested) {}

    private final Theme theme;
    private String badge;
    private String title;
    private String from, fromSub, to, toSub;
    private final List<Chip> chips = new ArrayList<>();
    private final List<Stat> stats = new ArrayList<>();
    private Seats seats;
    private String tagline;
    private boolean confetti;

    private TgCard(Theme theme) {
        this.theme = theme == null ? Theme.TRIP : theme;
    }

    public static TgCard of(Theme theme) {
        return new TgCard(theme);
    }

    public TgCard badge(String s) { this.badge = s; return this; }

    public TgCard title(String s) { this.title = s; return this; }

    /** Qayerdan (katta) va uning viloyati (kichik), qayerga — xuddi shunday. Tuman bo'lmasa — viloyat katta, pastida hech narsa */
    public TgCard route(String from, String fromSub, String to, String toSub) {
        this.from = from; this.fromSub = fromSub; this.to = to; this.toSub = toSub;
        return this;
    }

    public TgCard chip(Icon icon, String text) {
        if (text != null && !text.isBlank() && chips.size() < 4) chips.add(new Chip(icon, text.trim()));
        return this;
    }

    public TgCard stat(Icon icon, String value, String label) {
        if (value != null && label != null && stats.size() < 3) stats.add(new Stat(icon, value, label));
        return this;
    }

    public TgCard seats(int capacity, int taken, int suggested) {
        if (capacity > 0 && capacity <= 20) this.seats = new Seats(capacity, Math.max(0, taken), Math.max(0, suggested));
        return this;
    }

    public TgCard tagline(String s) { this.tagline = s; return this; }

    public TgCard confetti() { this.confetti = true; return this; }

    public Theme theme() { return theme; }
    public String badge() { return badge; }
    public String title() { return title; }
    public String from() { return from; }
    public String fromSub() { return fromSub; }
    public String to() { return to; }
    public String toSub() { return toSub; }
    public List<Chip> chips() { return List.copyOf(chips); }
    public List<Stat> stats() { return List.copyOf(stats); }
    public Seats seats() { return seats; }
    public String tagline() { return tagline; }
    public boolean hasConfetti() { return confetti; }

    /** Karta mazmunining barmoq izi: bir xil mazmun — bir xil rasm (Telegramga bir marta yuklanadi) */
    public String key() {
        String s = String.join("\u0001", String.valueOf(theme), n(badge), n(title), n(from), n(fromSub), n(to), n(toSub),
                chips.toString(), stats.toString(), String.valueOf(seats), n(tagline), String.valueOf(confetti), "v1");
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(h, 0, 16);
        } catch (Exception e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    private static String n(String s) {
        return s == null ? "" : s;
    }
}
