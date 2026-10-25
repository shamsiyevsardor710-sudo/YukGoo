package uz.yukgo.sms;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SMS pulini begonalar sarflab yubormasligi uchun cheklovlar ("SMS bombing"). Har bir SMS pul turadi, shuning uchun:
 *  • bitta tasdiqlash so'rovi uchun — ko'pi bilan 3 ta SMS (birinchisi va 2 marta qayta yuborish);
 *  • bitta raqamga sutkasiga — 5 ta;
 *  • bitta IP manzildan — soatiga 15 ta, sutkasiga 50 ta (mobil operatorlarda ko'p odam bitta IP'da bo'ladi);
 *  • hammasi bo'lib sutkasiga — SMS_DAILY_LIMIT (standart 500).
 * Chegaraga yetilsa kod Telegram bot orqali olinadi — ro'yxatdan o'tish to'xtab qolmaydi.
 */
@Slf4j
@Component
public class SmsGuard {
    static final int PER_REQUEST = 3;
    static final int PER_PHONE_DAY = 5;
    static final int PER_IP_HOUR = 15;
    static final int PER_IP_DAY = 50;
    private static final ZoneId TZ = ZoneId.of("Asia/Tashkent");

    public static final String LIMIT_NOTICE = "SMS yuborish chegarasiga yetildi. Kodni Telegram bot orqali oling.";

    private final int dailyLimit;
    private final Map<String, Deque<Instant>> byIp = new ConcurrentHashMap<>();
    private LocalDate day = LocalDate.now(TZ);
    private int sentToday;
    private boolean dailyWarned;

    public SmsGuard(Environment env) {
        int limit = 500;
        try {
            String v = env.getProperty("SMS_DAILY_LIMIT");
            if (v != null && !v.isBlank()) limit = Math.max(0, Integer.parseInt(v.trim()));
        } catch (NumberFormatException e) {
            log.warn("SMS_DAILY_LIMIT noto'g'ri, standart 500 ishlatiladi");
        }
        this.dailyLimit = limit;
    }

    /**
     * @param ip             so'rov yuborgan IP (noma'lum bo'lsa null)
     * @param sentForRequest shu tasdiqlash so'rovi uchun avval yuborilgan SMS'lar
     * @param sentToPhone24h shu raqamga oxirgi 24 soatda yuborilgan SMS'lar
     * @return null — yuborish mumkin, aks holda sabab (foydalanuvchiga ko'rsatiladi)
     */
    public synchronized String check(String ip, int sentForRequest, long sentToPhone24h) {
        return check(ip, sentForRequest, sentToPhone24h, Instant.now());
    }

    synchronized String check(String ip, int sentForRequest, long sentToPhone24h, Instant now) {
        rollDay(now);
        if (sentForRequest >= PER_REQUEST || sentToPhone24h >= PER_PHONE_DAY) return LIMIT_NOTICE;
        if (sentToday >= dailyLimit) {
            if (!dailyWarned) {
                dailyWarned = true;
                log.error("Bugungi SMS chegarasi ({}) tugadi — kodlar Telegram orqali yuboriladi. "
                        + "Kerak bo'lsa SMS_DAILY_LIMIT ni oshiring.", dailyLimit);
            }
            return LIMIT_NOTICE;
        }
        if (ip != null) {
            Deque<Instant> q = byIp.get(ip);
            if (q != null) {
                prune(q, now);
                long lastHour = q.stream().filter(t -> t.isAfter(now.minus(Duration.ofHours(1)))).count();
                if (lastHour >= PER_IP_HOUR || q.size() >= PER_IP_DAY) return LIMIT_NOTICE;
            }
        }
        return null;
    }

    /** SMS muvaffaqiyatli yuborilgandan keyin */
    public synchronized void record(String ip) {
        record(ip, Instant.now());
    }

    synchronized void record(String ip, Instant now) {
        rollDay(now);
        sentToday++;
        if (ip != null) {
            Deque<Instant> q = byIp.computeIfAbsent(ip, k -> new ArrayDeque<>());
            prune(q, now);
            q.addLast(now);
        }
        // Xotira o'sib ketmasin: bo'shab qolgan IP yozuvlari tozalanadi
        if (byIp.size() > 10_000) byIp.entrySet().removeIf(e -> { prune(e.getValue(), now); return e.getValue().isEmpty(); });
    }

    private void rollDay(Instant now) {
        LocalDate d = LocalDate.ofInstant(now, TZ);
        if (!d.equals(day)) {
            day = d;
            sentToday = 0;
            dailyWarned = false;
        }
    }

    private static void prune(Deque<Instant> q, Instant now) {
        Instant dayAgo = now.minus(Duration.ofHours(24));
        while (!q.isEmpty() && q.peekFirst().isBefore(dayAgo)) q.pollFirst();
    }
}
