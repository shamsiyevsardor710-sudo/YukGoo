package uz.yukgo.growth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.common.ApiException;
import uz.yukgo.growth.GrowthDtos.*;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.order.Order;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;
import uz.yukgo.wallet.MonetizationSettings;
import uz.yukgo.wallet.TxType;
import uz.yukgo.wallet.WalletService;

import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Yangi foydalanuvchilarni jalb qilish hisobi:
 *  • manba — qaysi kanal, hamkor yoki dala xodimi havolasidan kelgani (?src=KOD, t.me/BOT?start=src_KOD);
 *  • taklif — har bir foydalanuvchining shaxsiy havolasi (?ref=ID, t.me/BOT?start=ref_ID) va taklif bonusi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GrowthService {
    private static final Pattern SOURCE = Pattern.compile("[a-z0-9_-]{1,40}");

    private final UserRepository userRepository;
    private final SourceCodeRepository sourceRepository;
    private final AdminLogRepository logRepository;
    private final WalletService wallet;
    private final NotificationService notifications;
    private final TelegramClient telegram;
    private final CurrentUser currentUser;
    private final OrderRepository orders;

    /** Botga havola bilan kirgan, hali ro'yxatdan o'tmagan Telegram foydalanuvchilari: chat ID → manba va taklif */
    private record Pending(String source, String ref, Instant at) {}
    private static final Duration PENDING_TTL = Duration.ofDays(7);
    private static final int PENDING_MAX = 20_000;
    private final Map<Long, Pending> pending = new ConcurrentHashMap<>();

    /** Manba kodini tozalaydi: faqat kichik lotin harflari, raqam, _ va - (aks holda null) */
    public static String cleanSource(String raw) {
        if (raw == null) return null;
        String s = raw.trim().toLowerCase(Locale.ROOT);
        return SOURCE.matcher(s).matches() ? s : null;
    }

    /**
     * Bot havolasi bilan kelgan (t.me/BOT?start=src_KOD yoki ref_ID), lekin hali ro'yxatdan o'tmagan foydalanuvchi:
     * Mini App'ni boshqa yo'l bilan (masalan, menyu tugmasidan) ochib ro'yxatdan o'tsa ham manbasi yo'qolmasin.
     * Birinchi kelgan havola ustun. Faqat xotirada saqlanadi (ilova qayta ishga tushsa — unutiladi).
     */
    public void rememberStart(long chatId, String source, String ref) {
        String s = cleanSource(source);
        String r = ref != null && ref.matches("\\d{1,18}") ? ref : null;
        if (s == null && r == null) return;
        if (pending.size() >= PENDING_MAX) {
            Instant old = Instant.now().minus(PENDING_TTL);
            pending.values().removeIf(p -> p.at().isBefore(old));
            if (pending.size() >= PENDING_MAX) return;
        }
        pending.putIfAbsent(chatId, new Pending(s, r, Instant.now()));
    }

    /** Ro'yxatdan o'tayotgan foydalanuvchiga manba va taklif qilganni yozadi (noto'g'ri qiymatlar jim e'tiborsiz qoldiriladi). */
    public void attribute(User newUser, String source, String ref) {
        boolean none = (source == null || source.isBlank()) && (ref == null || ref.isBlank());
        if (none && newUser.getTelegramChatId() != null) {
            Pending p = pending.remove(newUser.getTelegramChatId());
            if (p != null && p.at().isAfter(Instant.now().minus(PENDING_TTL))) {
                source = p.source();
                ref = p.ref();
            }
        } else if (newUser.getTelegramChatId() != null) {
            pending.remove(newUser.getTelegramChatId());
        }
        newUser.setSignupSource(cleanSource(source));
        if (ref == null) return;
        String digits = ref.trim().replaceFirst("^(ref_|r)", "");
        if (!digits.matches("\\d{1,18}")) return;
        userRepository.findById(Long.valueOf(digits))
                .filter(r -> r.getStatus() == UserStatus.ACTIVE)
                .filter(r -> newUser.getPhone() == null || !newUser.getPhone().equals(r.getPhone()))
                .ifPresent(r -> newUser.setReferredById(r.getId()));
    }

    // =====================================================================
    // Taklif bonusi
    // =====================================================================

    /**
     * Buyurtma yetkazilganda (OrderService.deliver tranzaksiyasi ichida): haydovchi yoki yuboruvchi taklif havolasi orqali
     * kelgan bo'lsa va bu uning taklif qilgan odamsiz yakunlangan BIRINCHI buyurtmasi bo'lsa — ikkalasiga (u va taklif
     * qilganga) bonus. Taklif qilgan odamning o'zi bilan qilingan buyurtma hisoblanmaydi (o'zaro "soxta" buyurtma bilan
     * bonus yig'ilmasin). Bonus faqat daromad tizimi yoqilganda beriladi: balans faqat shunda ishlatiladi va ilova
     * bonus haqida faqat shunda aytadi.
     */
    public void onDelivered(Order o) {
        MonetizationSettings s = wallet.current();
        if (!s.isEnabled()) return;
        long bonus = s.referralBonusAmount();
        if (bonus <= 0) return;
        reward(o.getDriver(), o.getSender(), o, bonus);
        reward(o.getSender(), o.getDriver(), o, bonus);
    }

    private void reward(User referred, User counterparty, Order o, long bonus) {
        if (referred == null || referred.getReferredById() == null || referred.getReferralRewardedAt() != null) return;
        Long referrerId = referred.getReferredById();
        if (counterparty != null && referrerId.equals(counterparty.getId())) return;
        // Bir vaqtda ikki buyurtma yetkazilsa ham bonus bir marta: qator bloklanib, bazadagi qiymat qayta tekshiriladi
        userRepository.lockById(referred.getId());
        if (userRepository.referralRewardedAtOf(referred.getId()) != null) return;
        // Faqat birinchi buyurtma (haydovchi va yuk egasi sifatidagilari birga): bonus yoqilishidan oldin buyurtmalari
        // yakunlanganlar keyingi buyurtmada olmaydi
        long done = orders.countByDriverIdAndStatusAndSenderIdNot(referred.getId(), OrderStatus.DELIVERED, referrerId)
                + orders.countBySenderIdAndStatusAndDriverIdNot(referred.getId(), OrderStatus.DELIVERED, referrerId);
        if (done != 1) return;
        referred.setReferralRewardedAt(Instant.now());
        wallet.credit(referred.getId(), bonus, TxType.REFERRAL_BONUS, o.getId(), "Taklif bonusi: birinchi buyurtmangiz yakunlandi", null);
        notifications.notify(referred, "🎁 Taklif bonusi",
                "Birinchi buyurtmangiz yakunlandi — balansingizga " + money(bonus) + " qo'shildi", "#/profile");
        User referrer = userRepository.findById(referrerId).orElse(null);
        if (referrer != null && referrer.getStatus() == UserStatus.ACTIVE) {
            wallet.credit(referrerId, bonus, TxType.REFERRAL_BONUS, o.getId(),
                    "Taklif bonusi: " + referred.getFirstName() + " birinchi buyurtmasini yakunladi", null);
            notifications.notify(referrer, "🎁 Taklif bonusi",
                    "Siz taklif qilgan " + referred.getFirstName() + " birinchi buyurtmasini yakunladi — balansingizga "
                            + money(bonus) + " qo'shildi. Rahmat!", "#/profile");
        }
        log.info("Taklif bonusi: user={} referrer={} {} so'm", referred.getId(), referrerId, bonus);
    }

    // =====================================================================
    // Foydalanuvchi: shaxsiy taklif havolasi
    // =====================================================================

    @Transactional(readOnly = true)
    public ReferralDto myReferral() {
        User me = currentUser.get();
        return new ReferralDto(notifications.siteUrl("?ref=" + me.getId()), telegram.deepLink("ref_" + me.getId()),
                userRepository.countByReferredById(me.getId()),
                userRepository.countByReferredByIdAndReferralRewardedAtIsNotNull(me.getId()),
                wallet.current().referralBonusAmount());
    }

    // =====================================================================
    // Admin: manbalar hisoboti va havola kodlari
    // =====================================================================

    @Transactional(readOnly = true)
    public SourcesReport report(int days) {
        int d = days <= 0 ? 3650 : Math.min(days, 3650);
        Instant since = Instant.now().minus(Duration.ofDays(d));
        Map<String, SourceCode> saved = new LinkedHashMap<>();
        for (SourceCode c : sourceRepository.findAllByOrderByCreatedAtDesc()) saved.put(c.getCode(), c);

        Map<String, SourceStat> rows = new LinkedHashMap<>();
        for (Object[] r : userRepository.sourceStats(since)) {
            String code = (String) r[0];
            SourceCode c = saved.get(code);
            rows.put(code, new SourceStat(code, c != null ? c.getName() : defaultName(code), c != null ? c.getNote() : null,
                    c != null, num(r[1]), num(r[2]), num(r[3]), num(r[4]), (Instant) r[5], siteLink(code), botLink(code)));
        }
        // Hali hech kim kelmagan saqlangan kodlar ham ro'yxatda tursin (havolani ko'chirib olish uchun)
        for (SourceCode c : saved.values()) {
            rows.putIfAbsent(c.getCode(), new SourceStat(c.getCode(), c.getName(), c.getNote(), true, 0, 0, 0, 0, null,
                    siteLink(c.getCode()), botLink(c.getCode())));
        }
        List<SourceStat> list = new ArrayList<>(rows.values());
        list.sort(Comparator.comparingLong(SourceStat::users).reversed());

        List<Object[]> refRows = userRepository.referralStats(since, PageRequest.of(0, 20));
        Map<Long, User> referrers = new HashMap<>();
        userRepository.findAllById(refRows.stream().map(r -> (Long) r[0]).toList()).forEach(u -> referrers.put(u.getId(), u));
        List<ReferrerStat> top = refRows.stream().map(r -> {
            User u = referrers.get((Long) r[0]);
            return new ReferrerStat((Long) r[0], u == null ? "—" : u.getFullName(), u == null ? null : u.getPhone(), num(r[1]), num(r[2]));
        }).toList();

        return new SourcesReport(d, userRepository.countByCreatedAtGreaterThanEqual(since),
                userRepository.countBySignupSourceIsNotNullAndCreatedAtGreaterThanEqual(since), list, top);
    }

    @Transactional
    public SourceStat createSource(CreateSourceRequest req) {
        User me = requireStaff();
        String code = cleanSource(req.code());
        if (code == null) throw ApiException.badRequest("Kod: kichik lotin harflari, raqam, _ yoki -");
        if (sourceRepository.existsByCode(code)) throw ApiException.conflict("Bu kod allaqachon bor");
        SourceCode c = new SourceCode();
        c.setCode(code);
        c.setName(req.name().trim());
        c.setNote(req.note() == null || req.note().isBlank() ? null : req.note().trim());
        c.setCreatedBy(me.getFullName());
        sourceRepository.save(c);
        log(me, "SOURCE_CREATE", code + " — " + c.getName());
        return new SourceStat(code, c.getName(), c.getNote(), true, 0, 0, 0, 0, null, siteLink(code), botLink(code));
    }

    @Transactional
    public void deleteSource(String code) {
        User me = requireStaff();
        SourceCode c = sourceRepository.findByCode(code).orElseThrow(() -> ApiException.notFound("Kod topilmadi"));
        sourceRepository.delete(c);
        log(me, "SOURCE_DELETE", code + " — " + c.getName() + " (kelganlar hisobotda kod bilan qoladi)");
    }

    /** Sayt havolasi: /?src=KOD */
    public String siteLink(String code) {
        return notifications.siteUrl("?src=" + code);
    }

    /** Bot havolasi: t.me/BOT?start=src_KOD (Telegram start parametri 64 belgigacha) */
    public String botLink(String code) {
        return telegram.deepLink("src_" + code);
    }

    private static String defaultName(String code) {
        if (code.matches("ch\\d+")) return "Telegram kanal #" + code.substring(2);
        return code;
    }

    private User requireStaff() {
        User me = currentUser.get();
        if (!me.getRole().isStaff()) throw ApiException.forbidden("Faqat admin");
        return me;
    }

    private void log(User actor, String action, String details) {
        AdminLog l = new AdminLog();
        l.setActorId(actor.getId());
        l.setActorName(actor.getFullName() + " (" + actor.getRole() + ")");
        l.setAction(action);
        l.setDetails(details.length() > 500 ? details.substring(0, 500) : details);
        logRepository.save(l);
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static String money(long v) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(v).replace('\u00A0', ' ').replace('\u202F', ' ') + " so'm";
    }
}
