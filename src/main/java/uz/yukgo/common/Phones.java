package uz.yukgo.common;

/** O'zbekiston telefon raqamlarini +998XXXXXXXXX ko'rinishiga keltiradi. */
public final class Phones {
    private Phones() {}

    public static final String HIDDEN_IN_TEXT = "[raqam «Olaman»dan keyin ko'rinadi]";
    public static final String HIDDEN_CONTACT = "[kontakt «Olaman»dan keyin ko'rinadi]";

    /**
     * Matndagi O'zbekiston raqami: ixtiyoriy +998, operator yoki hudud kodi va yana 7 raqam. Raqamlar orasida bir-ikkita
     * probel, chiziqcha, nuqta yoki qavs bo'lishi mumkin (yangi qator emas) — "90 123 45 67", "+998 (90) 123-45-67",
     * "901234567". Yonidagi boshqa son ("… 67 2 kishi") raqamni buzmaydi.
     */
    private static final java.util.regex.Pattern PHONE_IN_TEXT = java.util.regex.Pattern.compile(
            "(?<![\\d+])(?:\\+?998[ \\t\\-.()]{0,2})?\\(?(?:20|33|50|55|61|62|65|66|67|69|70|71|72|73|74|75|76|77|78|79|88|90|91|93|94|95|97|98|99)"
                    + "\\)?(?:[ \\t\\-.()]{0,2}\\d){7}(?!\\d)");
    /** Narx: minglab guruhlangan va 000 bilan tugaydigan son ("200 000 000") */
    private static final java.util.regex.Pattern PRICE_SHAPE = java.util.regex.Pattern.compile("\\d{1,3}(?:[ .,]\\d{3})+");
    /** Bo'shliqsiz son ortidan pul birligi kelsa — narx ("200000000 so'm") */
    private static final java.util.regex.Pattern CURRENCY_AFTER = java.util.regex.Pattern.compile(
            "^[ \\t]*(?:so['‘’ʻ`]?m|sum|som|сўм|сум|mln|млн)(?![\\p{L}])",
            java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE);
    /** Telegram orqali bog'lanish: @username, t.me/... (e-pochtadagi @ tegilmaydi) */
    private static final java.util.regex.Pattern TG_CONTACT = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w.])(?:https?://)?(?:t\\.me|telegram\\.me)/[A-Za-z0-9_+/]+|(?<![\\w@.])@[A-Za-z][A-Za-z0-9_]{3,31}");

    /**
     * Haydovchi tanlanmagan yozishmada (va e'lon matnida) bog'lanish ma'lumoti yashiriladi — raqam vaqtidan oldin
     * tarqalmasin: telefon raqamlar va Telegram username / t.me havolalari. Narx ("1 500 000", "200 000 000 so'm"),
     * sana va soat tegilmaydi.
     */
    public static String maskInText(String text) {
        if (text == null || text.isEmpty()) return text;
        java.util.regex.Matcher m = PHONE_IN_TEXT.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String run = m.group();
            // Narx yaxlit bo'ladi (oxiri 000): "200 000 000" yoki "200000000 so'm"; telefon deyarli hech qachon shunday tugamaydi
            boolean round = run.endsWith("000");
            boolean grouped = round && PRICE_SHAPE.matcher(run).matches();
            boolean priced = round && run.chars().allMatch(Character::isDigit) && CURRENCY_AFTER.matcher(text.substring(m.end())).find();
            m.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(grouped || priced ? run : HIDDEN_IN_TEXT));
        }
        m.appendTail(sb);
        return TG_CONTACT.matcher(sb.toString()).replaceAll(java.util.regex.Matcher.quoteReplacement(HIDDEN_CONTACT));
    }

    public static String normalize(String raw) {
        if (raw == null) throw ApiException.badRequest("Telefon raqam kiritilmagan");
        String digits = raw.replaceAll("\\D", "");
        if (digits.length() == 9) digits = "998" + digits;
        if (digits.length() != 12 || !digits.startsWith("998")) {
            throw ApiException.badRequest("Telefon raqam noto'g'ri. Namuna: +998 90 123 45 67");
        }
        return "+" + digits;
    }

    /** Logga yozish uchun yashirilgan raqam: "+998901234567" → "+998*******67". */
    public static String mask(String phone) {
        if (phone == null || phone.length() < 7) return "***";
        return phone.substring(0, 4) + "*".repeat(phone.length() - 6) + phone.substring(phone.length() - 2);
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
