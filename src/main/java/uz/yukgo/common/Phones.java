package uz.yukgo.common;

/** O'zbekiston telefon raqamlarini +998XXXXXXXXX ko'rinishiga keltiradi. */
public final class Phones {
    private Phones() {}

    public static String normalize(String raw) {
        if (raw == null) throw ApiException.badRequest("Telefon raqam kiritilmagan");
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 9) digits = "998" + digits;
        if (digits.length() != 12 || !digits.startsWith("998")) {
            throw ApiException.badRequest("Telefon raqam noto'g'ri. Namuna: +998 90 123 45 67");
        }
        return "+" + digits;
    }

    /** Telegram kontaktidan kelgan raqamni xatosiz normallashtirish (null qaytarishi mumkin). */
    public static String tryNormalize(String raw) {
        try {
            return normalize(raw);
        } catch (ApiException e) {
            return null;
        }
    }
}
