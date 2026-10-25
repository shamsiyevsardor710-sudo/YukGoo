package uz.yukgo.payment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/** To'lov tizimlari so'rovlarini tekshirish uchun yordamchilar: Basic auth, IP ro'yxati, xeshlar. */
final class ProviderAuth {
    private ProviderAuth() {}

    /** "Authorization: Basic base64(login:parol)" sarlavhasidan [login, parol]; noto'g'ri bo'lsa — null */
    static String[] basic(String header) {
        if (header == null) return null;
        String h = header.trim();
        if (h.length() < 7 || !h.regionMatches(true, 0, "Basic ", 0, 6)) return null;
        String decoded;
        try {
            decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return null;
        }
        int sep = decoded.indexOf(':');
        if (sep < 0) return null;
        return new String[]{decoded.substring(0, sep), decoded.substring(sep + 1)};
    }

    /** Ikki satrni vaqt bo'yicha sizdirmasdan solishtirish (SHA-256 xeshlari orqali, uzunlikdan qat'i nazar) */
    static boolean same(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(sha256(a), sha256(b));
    }

    static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sha256Hex(String s) {
        return HexFormat.of().formatHex(sha256(s));
    }

    static String md5Hex(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Mijoz IP manzili ro'yxatdami. Ro'yxat bo'sh bo'lsa — hammaga ruxsat. Yozuvlar: aniq manzil yoki IPv4 tarmoq
     * ("185.234.113.0/24"); IPv4'ga o'girilgan IPv6 manzil (::ffff:1.2.3.4) IPv4 sifatida tekshiriladi.
     */
    static boolean ipAllowed(String ip, List<String> list) {
        if (list == null || list.isEmpty()) return true;
        if (ip == null || ip.isBlank()) return false;
        String client = ip.trim().toLowerCase(Locale.ROOT);
        if (client.startsWith("::ffff:") && ipv4(client.substring(7)) != null) client = client.substring(7);
        Long c4 = ipv4(client);
        for (String raw : list) {
            String e = raw.trim().toLowerCase(Locale.ROOT);
            if (e.isEmpty()) continue;
            int slash = e.indexOf('/');
            if (slash < 0) {
                if (e.equals(client)) return true;
                continue;
            }
            Long net = ipv4(e.substring(0, slash));
            String bitsText = e.substring(slash + 1);
            if (net == null || c4 == null || !bitsText.matches("\\d{1,2}")) continue;
            int bits = Integer.parseInt(bitsText);
            if (bits > 32) continue;
            long mask = bits == 0 ? 0 : (0xFFFFFFFFL << (32 - bits)) & 0xFFFFFFFFL;
            if ((c4 & mask) == (net & mask)) return true;
        }
        return false;
    }

    static Long ipv4(String s) {
        String[] p = s.split("\\.", -1);
        if (p.length != 4) return null;
        long v = 0;
        for (String part : p) {
            if (!part.matches("\\d{1,3}")) return null;
            int o = Integer.parseInt(part);
            if (o > 255) return null;
            v = (v << 8) | o;
        }
        return v;
    }
}
