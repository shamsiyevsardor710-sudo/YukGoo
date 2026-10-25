package uz.yukgo.payment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.config.AppProperties;
import uz.yukgo.notification.NotificationService;
import uz.yukgo.payment.PaymentDtos.AdminPaymentDto;
import uz.yukgo.payment.PaymentDtos.AdminSetup;
import uz.yukgo.payment.PaymentDtos.Options;
import uz.yukgo.payment.PaymentDtos.PaymentDto;
import uz.yukgo.payment.PaymentDtos.TopupResponse;
import uz.yukgo.user.User;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;
import uz.yukgo.wallet.TxType;
import uz.yukgo.wallet.WalletService;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.NumberFormat;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Hamyonni Click, Payme yoki Paynet orqali to'ldirish. Foydalanuvchi summani tanlaydi → to'lov sahifasiga o'tadi →
 * to'lov tizimi serverimizga xabar beradi (ClickService, PaymeService, PaynetService) → balans to'ldiriladi.
 * Foydalanuvchiga to'lov usullari faqat daromad tizimi yoqilgan bo'lsa ko'rsatiladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {
    /** To'lanmagan buyurtma shuncha vaqtdan keyin eskiradi (yangi to'lov urinishi qabul qilinmaydi) */
    static final Duration ORDER_TTL = Duration.ofHours(24);
    /** Bir foydalanuvchi bir soatda ochishi mumkin bo'lgan to'lovlar soni */
    static final int TOPUPS_PER_HOUR = 30;

    private final PaymentRepository payments;
    private final PaymentSettings settings;
    private final WalletService wallet;
    private final UserRepository users;
    private final NotificationService notifications;
    private final AppProperties props;
    private final PlatformTransactionManager txManager;

    // =====================================================================
    // Foydalanuvchi uchun
    // =====================================================================

    @Transactional(readOnly = true)
    public Options options(User u) {
        boolean on = wallet.current().isEnabled();
        boolean paynet = on && settings.paynetEnabled();
        // Payme sinov kassasida (PAYME_TEST) foydalanuvchilarga ko'rsatilmaydi: sinov kartasi bilan haqiqiy balans olinmasin
        return new Options(on && settings.clickEnabled(), on && settings.paymeEnabled() && !settings.paymeTest(), paynet,
                settings.minAmount(), settings.maxAmount(),
                paynet ? String.valueOf(u.getId()) : null, paynet ? paynetLink(u.getId(), null) : null);
    }

    @Transactional
    public TopupResponse topup(User u, PaymentProvider provider, long amount) {
        if (!wallet.current().isEnabled()) {
            throw ApiException.badRequest("Hozircha balansni to'ldirish shart emas");
        }
        return create(u, provider, amount, false);
    }

    /**
     * Admin sinovi: daromad tizimi o'chiq bo'lsa ham o'ziga to'lov ochadi (Click sinov dasturi va Payme sinov kassasi
     * haqiqiy to'lov raqamini so'raydi). Sinov to'lovi hech qachon balansga yozilmaydi.
     */
    @Transactional
    public TopupResponse testTopup(User admin, PaymentProvider provider, long amount) {
        if (provider == PaymentProvider.PAYNET) {
            throw ApiException.badRequest("Paynet'da to'lov oldindan ochilmaydi: Paynet sinovida hisob raqami sifatida ID ingizni bering");
        }
        TopupResponse r = create(admin, provider, amount, true);
        log.info("Sinov to'lovi #{} ochildi: {} ({}), {} so'm", r.id(), admin.getId(), provider, amount);
        return r;
    }

    private TopupResponse create(User u, PaymentProvider provider, long amount, boolean test) {
        if (amount < settings.minAmount() || amount > settings.maxAmount()) {
            throw ApiException.badRequest("Summa " + money(settings.minAmount()) + " dan " + money(settings.maxAmount()) + " gacha bo'lsin");
        }
        boolean enabled = switch (provider) {
            case CLICK -> settings.clickEnabled();
            case PAYME -> settings.paymeEnabled() && (test || !settings.paymeTest());
            case PAYNET -> settings.paynetEnabled();
        };
        if (!enabled) throw ApiException.badRequest(provider.label() + " orqali to'lov hozircha ulanmagan");
        if (provider == PaymentProvider.PAYNET) {
            // Paynet'da buyurtma oldindan yaratilmaydi: foydalanuvchi Paynet ilovasida hisob raqamini kiritib, o'zi to'laydi
            return new TopupResponse(null, provider, amount, paynetLink(u.getId(), amount), String.valueOf(u.getId()));
        }
        if (payments.countByUserIdAndCreatedAtAfter(u.getId(), Instant.now().minus(Duration.ofHours(1))) >= TOPUPS_PER_HOUR) {
            throw ApiException.badRequest("Juda ko'p urinish. Birozdan keyin qayta urinib ko'ring");
        }
        Payment p = new Payment();
        p.setUserId(u.getId());
        p.setProvider(provider);
        p.setAmount(amount);
        if (test) p.setTestPayment(true);
        p = payments.save(p);
        String url = provider == PaymentProvider.CLICK ? clickUrl(p) : paymeUrl(p);
        log.info("To'lov #{} yaratildi: user={}, {} so'm, {}", p.getId(), u.getId(), amount, provider);
        return new TopupResponse(p.getId(), provider, amount, url, null);
    }

    /** Foydalanuvchining o'z to'lovi holati (to'lov sahifasi ochilgandan keyin ilova shuni kuzatib turadi) */
    @Transactional(readOnly = true)
    public PaymentDto status(User u, Long id) {
        Payment root = payments.findById(id).filter(p -> p.getUserId().equals(u.getId()))
                .orElseThrow(() -> ApiException.notFound("To'lov topilmadi"));
        Long rootId = root.rootId();
        List<Payment> family = payments.family(rootId);
        Payment paid = family.stream().filter(p -> p.getStatus() == PaymentStatus.PAID).findFirst().orElse(null);
        String status;
        if (paid != null) status = "PAID";
        else if (family.stream().anyMatch(p -> p.getStatus() == PaymentStatus.PREPARED)) status = "PENDING";
        else if (family.stream().allMatch(p -> p.getStatus() == PaymentStatus.CANCELLED)) status = "CANCELLED";
        else status = "CREATED";
        return new PaymentDto(rootId, root.getProvider(), root.getAmount(), status, root.getCreatedAt(),
                paid == null ? null : paid.getPaidAt());
    }

    /** Foydalanuvchining shu tizim orqali eng oxirgi to'lovi (Paynet: ilova to'lov tushganini shu bilan biladi); yo'q bo'lsa — null */
    @Transactional(readOnly = true)
    public PaymentDto latest(User u, PaymentProvider provider) {
        return payments.findFirstByUserIdAndProviderOrderByIdDesc(u.getId(), provider)
                .map(p -> new PaymentDto(p.getId(), p.getProvider(), p.getAmount(), p.getStatus() == PaymentStatus.PREPARED ? "PENDING"
                        : p.getStatus().name(), p.getCreatedAt(), p.getPaidAt()))
                .orElse(null);
    }

    // =====================================================================
    // Admin
    // =====================================================================

    @Transactional(readOnly = true)
    public PageResponse<AdminPaymentDto> adminList(int page) {
        Page<Payment> p = payments.findAllByOrderByIdDesc(PageRequest.of(Math.max(0, page), 30));
        Set<Long> ids = p.getContent().stream().map(Payment::getUserId).collect(Collectors.toSet());
        Map<Long, User> byId = users.findAllById(ids).stream().collect(Collectors.toMap(User::getId, Function.identity()));
        return PageResponse.of(p, x -> {
            User u = byId.get(x.getUserId());
            return new AdminPaymentDto(x.getId(), x.getParentId(), x.getUserId(), u == null ? null : u.getFullName(),
                    u == null ? null : u.getPhone(), x.getProvider(), x.getAmount(), x.getStatus(), x.getProviderTransId(),
                    x.getCreatedAt(), x.getPaidAt(), x.getCancelledAt(), x.getNote(), x.isTest());
        });
    }

    /** Qaysi tizimlar ulangan va ularning kabinetiga yoziladigan manzillar */
    public AdminSetup adminSetup() {
        String base = props.publicUrl() == null ? "" : props.publicUrl().trim().replaceAll("/+$", "");
        Map<String, String> urls = new LinkedHashMap<>();
        urls.put("clickPrepare", base + "/api/pay/click/prepare");
        urls.put("clickComplete", base + "/api/pay/click/complete");
        urls.put("payme", base + "/api/pay/payme");
        urls.put("paynet", base + "/api/pay/paynet");
        return new AdminSetup(settings.clickEnabled(), settings.paymeEnabled(), settings.paymeTest(), settings.paynetEnabled(),
                settings.paynetCredentials(), !settings.paynetServiceIds().isEmpty(), settings.minAmount(), settings.maxAmount(), urls);
    }

    // =====================================================================
    // To'lov tizimlari xizmatlari uchun (ular o'z tranzaksiyasi ichida chaqiradi)
    // =====================================================================

    /** Pul yechildi: to'lov "to'langan" bo'ladi va balans to'ldiriladi (sinov to'lovi — balansga yozilmaydi) */
    void markPaid(Payment p, String note) {
        p.setStatus(PaymentStatus.PAID);
        p.setPaidAt(Instant.now());
        if (p.isTest()) {
            log.info("Sinov to'lovi #{} ({}) to'landi — balansga yozilmadi", p.getId(), p.getProvider());
            return;
        }
        wallet.credit(p.getUserId(), p.getAmount(), TxType.TOPUP, null, note, null);
        log.info("To'lov #{} qabul qilindi ({}): user={}, {} so'm", p.getId(), p.getProvider(), p.getUserId(), p.getAmount());
        Long userId = p.getUserId();
        String body = "+" + money(p.getAmount()) + " — " + p.getProvider().label() + " orqali";
        // Xabar faqat to'lov bazaga yozilgandan keyin ketadi (xato bo'lib hammasi bekor bo'lsa — "to'ldirildi" demaymiz)
        afterCommit(() -> users.findById(userId).ifPresent(u -> notifications.notify(u, "Balans to'ldirildi", body, "#/profile")));
    }

    /** To'langan to'lovni qaytarish: summa balansdan yechiladi. Pul ishlatib bo'lingan bo'lsa — false (hech narsa o'zgarmaydi) */
    boolean reverse(Payment p, String note) {
        if (p.isTest()) {
            p.setStatus(PaymentStatus.CANCELLED);
            p.setCancelledAt(Instant.now());
            log.info("Sinov to'lovi #{} ({}) bekor qilindi", p.getId(), p.getProvider());
            return true;
        }
        if (!wallet.reverseTopup(p.getUserId(), p.getAmount(), note)) {
            log.warn("To'lov #{} ({}) qaytarilmadi: balansda {} so'm yo'q", p.getId(), p.getProvider(), p.getAmount());
            return false;
        }
        p.setStatus(PaymentStatus.CANCELLED);
        p.setCancelledAt(Instant.now());
        log.info("To'lov #{} qaytarildi ({}): user={}, {} so'm", p.getId(), p.getProvider(), p.getUserId(), p.getAmount());
        return true;
    }

    void cancel(Payment p, String note) {
        p.setStatus(PaymentStatus.CANCELLED);
        p.setCancelledAt(Instant.now());
        if (note != null) p.setNote(note.length() > 300 ? note.substring(0, 300) : note);
    }

    /** Asl to'lov qatori hali birorta tranzaksiyaga bog'lanmagan (birinchi urinish shu qatorning o'zida yoziladi) */
    static boolean unused(Payment root) {
        return root.getProviderTransId() == null && root.getStatus() == PaymentStatus.CREATED;
    }

    /** Qayta urinish uchun asl to'lovning nusxasi (o'sha foydalanuvchi, summa va sinov belgisi) */
    Payment child(Payment root) {
        Payment c = new Payment();
        c.setUserId(root.getUserId());
        c.setProvider(root.getProvider());
        c.setAmount(root.getAmount());
        c.setParentId(root.getId());
        c.setTestPayment(root.getTestPayment());
        return c;
    }

    /**
     * To'lov egasi faol foydalanuvchimi. Hisobini o'chirgan yoki bloklangan foydalanuvchiga yangi to'lov qabul qilinmaydi:
     * o'chirilgan hisobdagi pulni na foydalanuvchi, na ega qaytarib ololmaydi.
     */
    boolean ownerActive(Long userId) {
        return users.findById(userId).map(u -> u.getStatus() == UserStatus.ACTIVE).orElse(false);
    }

    /** Buyurtma eskirganmi: yaratilganiga ORDER_TTL dan ko'p vaqt o'tgan — yangi to'lov urinishi qabul qilinmaydi */
    static boolean orderExpired(Payment root) {
        return root.getCreatedAt() != null && root.getCreatedAt().plus(ORDER_TTL).isBefore(Instant.now());
    }

    private void afterCommit(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    // Asosiy tranzaksiya yopilgan: bildirishnoma o'zining yangi tranzaksiyasida yoziladi
                    TransactionTemplate tx = new TransactionTemplate(txManager);
                    tx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                    tx.executeWithoutResult(s -> action.run());
                } catch (RuntimeException e) {
                    log.warn("To'lov bildirishnomasi yuborilmadi: {}", e.toString());
                }
            }
        });
    }

    // =====================================================================
    // To'lov sahifalari havolalari
    // =====================================================================

    /** To'lovdan keyin foydalanuvchi qaytadigan manzil (server uni ilovaning profil sahifasiga yo'naltiradi) */
    String returnUrl(Long paymentId) {
        String base = props.publicUrl() == null ? "" : props.publicUrl().trim().replaceAll("/+$", "");
        return base + "/pay/return?p=" + paymentId;
    }

    String clickUrl(Payment p) {
        return "https://my.click.uz/services/pay?service_id=" + enc(settings.clickServiceId())
                + "&merchant_id=" + enc(settings.clickMerchantId())
                + "&amount=" + p.getAmount() + ".00"
                + "&transaction_param=" + p.getId()
                + "&return_url=" + enc(returnUrl(p.getId()));
    }

    /** Payme checkout: parametrlar base64 ichida — m=kassa;ac.order_id=ID;a=tiyin;c=qaytish manzili;l=til */
    String paymeUrl(Payment p) {
        String params = "m=" + settings.paymeMerchantId()
                + ";ac." + settings.paymeAccountField() + "=" + p.getId()
                + ";a=" + p.getAmount() * 100
                + ";c=" + returnUrl(p.getId())
                + ";l=uz";
        String base = settings.paymeTest() ? "https://test.paycom.uz/" : "https://checkout.paycom.uz/";
        return base + Base64.getEncoder().encodeToString(params.getBytes(StandardCharsets.UTF_8));
    }

    /** Paynet ilovasini shu hisob (va summa, tiyinda) bilan ochadigan havola; PAYNET_MERCHANT_ID berilmagan bo'lsa — null */
    String paynetLink(Long userId, Long amount) {
        String m = settings.paynetMerchantId();
        if (m.isEmpty()) return null;
        return "https://app.paynet.uz/?m=" + enc(m) + "&c=" + userId + (amount != null ? "&a=" + amount * 100 : "");
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static String money(long v) {
        return NumberFormat.getIntegerInstance(Locale.ROOT).format(v).replace(',', ' ') + " so'm";
    }
}
