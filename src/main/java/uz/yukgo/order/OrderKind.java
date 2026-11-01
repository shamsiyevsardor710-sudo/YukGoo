package uz.yukgo.order;

import uz.yukgo.user.BodyType;

import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;

/** E'lon turi: yuk, pochta (kichik jo'natma) yoki yo'lovchi */
public enum OrderKind {
    CARGO,      // Yuk
    PARCEL,     // Pochta: hujjat, kichik quti — yo'l-yo'lakay olib ketiladi
    PASSENGER;  // Yo'lovchi: viloyatdan viloyatga, tumandan tumanga

    /** E'lon kartasi va bildirishnomalardagi nomi */
    public String label() {
        return switch (this) {
            case CARGO -> "Yuk";
            case PARCEL -> "Pochta";
            case PASSENGER -> "Yo'lovchi";
        };
    }

    public String emoji() {
        return switch (this) {
            case CARGO -> "📦";
            case PARCEL -> "✉️";
            case PASSENGER -> "🧍";
        };
    }

    /**
     * Haydovchi o'zi tanlamagan bo'lsa — mashinasiga qarab qaysi e'lonlar haqida xabar oladi:
     * yengil mashina — yo'lovchi va pochta; furgon — hammasi; yuk mashinalari — yuk va pochta.
     */
    public static Set<OrderKind> defaultsFor(BodyType body) {
        BodyType b = BodyType.canonical(body);
        if (b == null) return EnumSet.allOf(OrderKind.class);
        return switch (b) {
            case SEDAN -> EnumSet.of(PASSENGER, PARCEL);
            case TRUCK, BORT, CONTAINER -> EnumSet.of(CARGO, PARCEL);
            default -> EnumSet.allOf(OrderKind.class);
        };
    }

    /** "CARGO,PARCEL" ko'rinishidagi satrdan (noma'lum qiymatlar tashlab yuboriladi); bo'sh bo'lsa — bo'sh to'plam */
    public static Set<OrderKind> parse(String csv) {
        Set<OrderKind> out = EnumSet.noneOf(OrderKind.class);
        if (csv == null) return out;
        for (String s : csv.split(",")) {
            try {
                out.add(OrderKind.valueOf(s.trim()));
            } catch (IllegalArgumentException ignored) {
                // eski yoki noto'g'ri qiymat
            }
        }
        return out;
    }

    public static String format(Set<OrderKind> kinds) {
        Set<String> names = new LinkedHashSet<>();
        for (OrderKind k : values()) if (kinds.contains(k)) names.add(k.name());
        return String.join(",", names);
    }
}
