package uz.yukgo.wallet;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.admin.AdminLog;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.common.Phones;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.user.Role;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.wallet.WalletDtos.AdjustRequest;
import uz.yukgo.wallet.WalletDtos.AdminTxDto;
import uz.yukgo.wallet.WalletDtos.PricingDto;
import uz.yukgo.wallet.WalletDtos.SettingsRequest;
import uz.yukgo.wallet.WalletDtos.TxDto;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hamyon va platforma daromadi.
 *  • Komissiya — haydovchiga buyurtma berilganda yechiladi: u "Olish" tugmasini bosganda
 *    yoki yuboruvchi uning taklifini qabul qilganda. Yuboruvchi yoki admin bekor qilsa — qaytariladi,
 *    haydovchi o'zi voz kechsa — qaytarilmaydi.
 *  • Shoshilinch to'lovi — yuboruvchi e'lonni shoshilinch qilganda yechiladi.
 *  • Hammasi faqat MonetizationSettings.enabled = true bo'lganda ishlaydi (boshida o'chiq).
 *  • Balansni hozircha ega qo'lda to'ldiradi; keyinchalik shu yerga Payme/Click ulanadi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WalletService {
    private static final ZoneId TZ = ZoneId.of("Asia/Tashkent");
    /** Daromad = shu turlar summasining teskari ishorasi (yechishlar manfiy, qaytarishlar musbat) */
    private static final List<TxType> REVENUE_TYPES = List.of(TxType.COMMISSION, TxType.URGENT_FEE, TxType.COMMISSION_REFUND);

    private final WalletRepository walletRepository;
    private final WalletInitializer walletInitializer;
    private final WalletTransactionRepository txRepository;
    private final MonetizationSettingsRepository settingsRepository;
    private final UserRepository userRepository;
    private final AdminLogRepository logRepository;
    private final NotificationService notifications;
    private final CurrentUser currentUser;

    // =====================================================================
    // Sozlamalar va narxlar
    // =====================================================================

    /** Joriy sozlamalar; bazada hali yo'q bo'lsa — standart (o'chiq) qiymatlar. Qaytgan obyektni o'zgartirmang. */
    @Transactional(readOnly = true)
    public MonetizationSettings current() {
        return settingsRepository.findById(MonetizationSettings.ID).orElseGet(MonetizationSettings::new);
    }

    /** Haydovchi shu narxdagi buyurtmani olganda yechiladigan komissiya (tizim o'chiq bo'lsa 0). */
    public long commissionFor(Long price) {
        MonetizationSettings s = current();
        return s.isEnabled() ? calcCommission(s, price) : 0;
    }

    /** Shoshilinch e'lon narxi (tizim o'chiq bo'lsa 0). */
    public long urgentFee() {
        MonetizationSettings s = current();
        return s.isEnabled() ? Math.max(0, s.getUrgentFee()) : 0;
    }

    /** Foiz, eng kam va eng ko'p chegaralar bo'yicha; 100 so'mga yaxlitlanadi. Frontenddagi hisob bilan bir xil. */
    static long calcCommission(MonetizationSettings s, Long price) {
        long base = price == null ? 0 : Math.round(price * s.getCommissionPercent() / 100.0);
        long c = Math.max(base, s.getCommissionMin());
        if (s.getCommissionMax() > 0) c = Math.min(c, s.getCommissionMax());
        return Math.max(0, Math.round(c / 100.0) * 100);
    }

    // =====================================================================
    // Balans
    // =====================================================================

    @Transactional(readOnly = true)
    public long balance(Long userId) {
        return walletRepository.findById(userId).map(Wallet::getBalance).orElse(0L);
    }

    @Transactional(readOnly = true)
    public PageResponse<TxDto> history(Long userId, int page) {
        return PageResponse.of(txRepository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(Math.max(0, page), 20)),
                TxDto::of);
    }

    /** Pul yechish. Yetmasa — xato, butun tranzaksiya (masalan, buyurtma yaratish) bekor bo'ladi. */
    @Transactional
    public void charge(User user, long amount, TxType type, Long orderId, String note, String insufficientMessage) {
        if (amount <= 0) return;
        Wallet w = lockOrCreate(user.getId());
        if (w.getBalance() < amount) {
            throw ApiException.badRequest(insufficientMessage != null ? insufficientMessage
                    : "Balansingizda mablag' yetarli emas: " + money(amount) + " kerak, balansingiz " + money(w.getBalance()) + ".");
        }
        apply(w, -amount, type, orderId, note, null);
    }

    @Transactional
    public void credit(Long userId, long amount, TxType type, Long orderId, String note, String actorName) {
        if (amount <= 0) return;
        apply(lockOrCreate(userId), amount, type, orderId, note, actorName);
    }

    // =====================================================================
    // Buyurtmalar bilan bog'liq to'lovlar
    // =====================================================================

    /** Taklif yuborishdan oldin: qabul qilinsa komissiyani to'lay oladimi? */
    @Transactional(readOnly = true)
    public void requireBalanceForCommission(User driver, Long price) {
        long fee = commissionFor(price);
        if (fee <= 0) return;
        long bal = balance(driver.getId());
        if (bal < fee) {
            throw ApiException.badRequest("Taklifingiz qabul qilinsa, balansingizdan " + money(fee)
                    + " komissiya yechiladi. Balansingiz: " + money(bal) + ". Avval balansni to'ldiring.");
        }
    }

    /**
     * Haydovchiga buyurtma berilganda komissiyani yechadi.
     *
     * @param byDriver haydovchi o'zi "Olish" tugmasini bosganmi (xato matni shunga qarab tanlanadi)
     * @return yechilgan summa (0 — tizim o'chiq)
     */
    @Transactional
    public long chargeCommission(User driver, Long orderId, Long price, String orderTitle, boolean byDriver) {
        long fee = commissionFor(price);
        if (fee <= 0) return 0;
        String msg = byDriver
                ? "Buyurtmani olish uchun balansingizda " + money(fee) + " (komissiya) bo'lishi kerak. Balansni to'ldiring."
                : "Bu haydovchining balansida komissiya uchun mablag' yetarli emas. Boshqa taklifni tanlang yoki haydovchiga balansni to'ldirishni ayting.";
        charge(driver, fee, TxType.COMMISSION, orderId, "Buyurtma #" + orderId + ": " + orderTitle, msg);
        return fee;
    }

    @Transactional
    public void refundCommission(Long driverId, Long orderId, long amount, String reason) {
        credit(driverId, amount, TxType.COMMISSION_REFUND, orderId, reason, null);
    }

    // =====================================================================
    // Ega va admin
    // =====================================================================

    /** Daromad paneli: sozlamalar va tushum (admin ko'radi, faqat ega o'zgartiradi). */
    @Transactional(readOnly = true)
    public Map<String, Object> adminOverview() {
        User me = currentUser.get();
        MonetizationSettings s = current();
        LocalDate today = LocalDate.now(TZ);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("settings", PricingDto.of(s));
        m.put("canEdit", me.getRole() == Role.OWNER);
        m.put("revenueToday", -txRepository.sumAmounts(REVENUE_TYPES, today.atStartOfDay(TZ).toInstant()));
        m.put("revenueMonth", -txRepository.sumAmounts(REVENUE_TYPES, today.withDayOfMonth(1).atStartOfDay(TZ).toInstant()));
        m.put("revenueTotal", -txRepository.sumAmounts(REVENUE_TYPES, Instant.EPOCH));
        m.put("walletsTotal", walletRepository.sumBalances());
        m.put("updatedAt", s.getUpdatedAt());
        m.put("updatedBy", s.getUpdatedBy());
        return m;
    }

    @Transactional
    public PricingDto updateSettings(SettingsRequest req) {
        User me = requireOwner();
        if (req.commissionMax() > 0 && req.commissionMax() < req.commissionMin()) {
            throw ApiException.badRequest("Eng ko'p komissiya eng kamidan kichik bo'lmasin (yoki 0 — cheklovsiz)");
        }
        MonetizationSettings s = settingsRepository.findById(MonetizationSettings.ID).orElseGet(MonetizationSettings::new);
        boolean wasEnabled = s.isEnabled();
        s.setEnabled(req.enabled());
        s.setCommissionPercent(req.commissionPercent());
        s.setCommissionMin(req.commissionMin());
        s.setCommissionMax(req.commissionMax());
        s.setUrgentFee(req.urgentFee());
        s.setTopupInfo(blankToNull(req.topupInfo()));
        s.setUpdatedAt(Instant.now());
        s.setUpdatedBy(me.getFullName());
        settingsRepository.save(s);
        String action = wasEnabled == req.enabled() ? "MONETIZATION_UPDATE" : req.enabled() ? "MONETIZATION_ON" : "MONETIZATION_OFF";
        log(me, action, null, "Komissiya " + req.commissionPercent() + "% (" + money(req.commissionMin()) + " – "
                + (req.commissionMax() > 0 ? money(req.commissionMax()) : "cheklovsiz") + "), shoshilinch " + money(req.urgentFee()));
        return PricingDto.of(s);
    }

    /** Ega: balansni qo'lda to'ldirish (+) yoki yechish (−). Balans manfiy bo'lishiga yo'l qo'yilmaydi. */
    @Transactional
    public Map<String, Object> adjust(AdjustRequest req) {
        User me = requireOwner();
        String phone = Phones.tryNormalize(req.phone());
        if (phone == null) throw ApiException.badRequest("Telefon raqam noto'g'ri");
        User target = userRepository.findByPhone(phone)
                .orElseThrow(() -> ApiException.notFound("Bu raqam bilan foydalanuvchi topilmadi"));
        long amount = req.amount();
        if (amount == 0) throw ApiException.badRequest("Summani kiriting");
        Wallet w = lockOrCreate(target.getId());
        if (w.getBalance() + amount < 0) {
            throw ApiException.badRequest("Balans manfiy bo'lib qoladi. Hozirgi balans: " + money(w.getBalance()));
        }
        String note = blankToNull(req.note());
        apply(w, amount, amount > 0 ? TxType.TOPUP : TxType.ADJUSTMENT, null,
                note != null ? note : amount > 0 ? "Balans to'ldirildi" : "Balansdan yechildi", me.getFullName());
        log(me, amount > 0 ? "BALANCE_TOPUP" : "BALANCE_DEBIT", target, money(Math.abs(amount)) + (note != null ? ": " + note : ""));
        notifications.notify(target, amount > 0 ? "💰 Balansingiz to'ldirildi" : "Balansingizdan yechildi",
                (amount > 0 ? "+" : "−") + money(Math.abs(amount)) + ". Joriy balans: " + money(w.getBalance()), "#/profile");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", target.getId());
        m.put("userName", target.getFullName());
        m.put("phone", target.getPhone());
        m.put("balance", w.getBalance());
        return m;
    }

    @Transactional(readOnly = true)
    public PageResponse<AdminTxDto> allTransactions(int page) {
        Page<WalletTransaction> p = txRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(Math.max(0, page), 30));
        Set<Long> ids = p.getContent().stream().map(WalletTransaction::getUserId).collect(Collectors.toSet());
        Map<Long, User> users = userRepository.findAllById(ids).stream()
                .collect(Collectors.toMap(User::getId, Function.identity()));
        return PageResponse.of(p, t -> AdminTxDto.of(t, users.get(t.getUserId())));
    }

    // =====================================================================
    // Yordamchi
    // =====================================================================

    private User requireOwner() {
        User me = currentUser.get();
        if (me.getRole() != Role.OWNER) throw ApiException.forbidden("Pul bilan bog'liq amallarni faqat ega bajara oladi");
        return me;
    }

    /** Hamyon qatorini bloklab oladi; bo'lmasa (alohida tranzaksiyada) yaratib, keyin bloklaydi. */
    private Wallet lockOrCreate(Long userId) {
        Optional<Wallet> locked = walletRepository.lockByUserId(userId);
        if (locked.isPresent()) return locked.get();
        try {
            walletInitializer.createIfMissing(userId);
        } catch (DataIntegrityViolationException e) {
            // parallel so'rov hamyonni shu lahzada yaratib bo'ldi — davom etamiz
        }
        return walletRepository.lockByUserId(userId)
                .orElseThrow(() -> ApiException.conflict("Hamyonni ochib bo'lmadi, qayta urinib ko'ring"));
    }

    private void apply(Wallet w, long delta, TxType type, Long orderId, String note, String actorName) {
        w.setBalance(w.getBalance() + delta);
        w.setUpdatedAt(Instant.now());
        WalletTransaction t = new WalletTransaction();
        t.setUserId(w.getUserId());
        t.setAmount(delta);
        t.setBalanceAfter(w.getBalance());
        t.setType(type);
        t.setOrderId(orderId);
        t.setNote(cut(note, 300));
        t.setActorName(cut(actorName, 255));
        txRepository.save(t);
        log.info("Balans: user={} {} {} ({}), qoldiq {}", w.getUserId(), delta > 0 ? "+" : "", delta, type, w.getBalance());
    }

    private void log(User actor, String action, User target, String details) {
        AdminLog l = new AdminLog();
        l.setActorId(actor.getId());
        l.setActorName(actor.getFullName() + " (" + actor.getRole() + ")");
        l.setAction(action);
        if (target != null) {
            l.setTargetUserId(target.getId());
            l.setTargetName(target.getFullName());
        }
        l.setDetails(cut(details, 500));
        logRepository.save(l);
    }

    static String money(long v) {
        return NumberFormat.getIntegerInstance(Locale.FRANCE).format(v)
                .replace(' ', ' ').replace(' ', ' ') + " so'm";
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
