package uz.yukgo.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.ClientIp;
import uz.yukgo.config.AppProperties;
import uz.yukgo.sms.SmsGuard;
import uz.yukgo.sms.SmsSender;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.telegram.TelegramLink;
import uz.yukgo.telegram.TelegramLinkRepository;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Tasdiqlash kodi yuborish: ro'yxatdan o'tish, parolni tiklash va telefon raqamni o'zgartirish uchun.
 *
 * 1) SMS sozlangan bo'lsa (Eskiz) — kod haqiqiy telefonga SMS bo'lib boradi. Har SMS pul turadi, shuning uchun
 *    SmsGuard cheklovlari bor; chegaraga yetilsa yoki SMS ketmasa — 2-yo'l.
 * 2) Telegram bot: bot telefon raqamga o'zi birinchi bo'lib yoza olmaydi, shuning uchun raqam botga avval
 *    ulangan bo'lsa kod darhol botdan keladi, aks holda sayt t.me/BOT?start=TOKEN havolasini beradi —
 *    foydalanuvchi botda "Telefon raqamni yuborish" tugmasini bosadi va kodni oladi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration VERIFIED_WINDOW = Duration.ofMinutes(30);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(45);
    private static final Duration SMS_RESEND_COOLDOWN = Duration.ofSeconds(60);
    static final String CHANNEL_SMS = "SMS";
    static final String CHANNEL_TELEGRAM = "TELEGRAM";
    /** SMS matni. Eskiz kabinetida aynan shu matn shablon sifatida tasdiqlanadi (kod — o'zgaruvchi qism). */
    static final String DEFAULT_SMS_TEMPLATE = "YukGo: tasdiqlash kodingiz {code}. Uni hech kimga bermang.";

    private final VerificationRequestRepository repository;
    private final TelegramLinkRepository linkRepository;
    private final TelegramClient telegram;
    private final AppProperties props;
    private final SmsSender sms;
    private final SmsGuard smsGuard;
    private final Environment env;

    /**
     * @param channel "SMS" — kod SMS bo'lib ketdi; "TELEGRAM" — bot orqali; null — hali yuborilmagan (botni kutyapti)
     * @param notice  foydalanuvchiga izoh (masalan, SMS chegarasi tugagani)
     */
    public record StartResult(String requestId, boolean codeSent, String botLink, String botUsername,
                              Instant expiresAt, String devCode, String channel, String notice) {}

    /** Har kuni tunda: 2 kundan eski tasdiqlash so'rovlari (telefon raqam, kod izi) o'chiriladi — keraksiz saqlanmasin */
    @Scheduled(cron = "0 45 3 * * *")
    @Transactional
    public void purgeOld() {
        int n = repository.deleteCreatedBefore(Instant.now().minus(Duration.ofDays(2)));
        if (n > 0) log.info("Eski tasdiqlash so'rovlari o'chirildi: {}", n);
    }

    @Transactional
    public StartResult start(String phone, VerificationPurpose purpose, Long userId) {
        long recent = repository.countByPhoneAndCreatedAtAfter(phone, Instant.now().minus(Duration.ofHours(1)));
        if (recent >= 10) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Juda ko'p urinish. Bir soatdan keyin qayta urinib ko'ring");
        }
        VerificationRequest vr = new VerificationRequest();
        vr.setId(UUID.randomUUID().toString());
        vr.setPhone(phone);
        vr.setPurpose(purpose);
        vr.setUserId(userId);
        vr.setLinkToken(randomToken());
        // ID qo'lda berilgani uchun save() merge qiladi va boshqa (managed) nusxani qaytaradi
        vr = repository.save(vr);
        return deliver(vr);
    }

    @Transactional
    public StartResult resend(String requestId) {
        VerificationRequest vr = getActive(requestId);
        Duration cooldown = CHANNEL_SMS.equals(vr.getChannel()) ? SMS_RESEND_COOLDOWN : RESEND_COOLDOWN;
        if (vr.getCodeSentAt() != null && vr.getCodeSentAt().plus(cooldown).isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Kodni qayta so'rashdan oldin biroz kuting");
        }
        return deliver(vr);
    }

    /** So'rov holati (frontend bot orqali ulanishni kutayotganda so'rab turadi). */
    @Transactional(readOnly = true)
    public StartResult status(String requestId) {
        VerificationRequest vr = getActive(requestId);
        boolean sent = vr.getCodeSentAt() != null && (CHANNEL_SMS.equals(vr.getChannel()) || telegram.enabled());
        return toResult(vr, sent, null, null);
    }

    /** Kodni yuboradi: avval SMS (sozlangan va chegara ichida bo'lsa), bo'lmasa — Telegram bot. */
    private StartResult deliver(VerificationRequest vr) {
        String notice = null;
        if (sms.enabled()) {
            String ip = ClientIp.current();
            long toPhone = repository.sumSmsSince(vr.getPhone(), Instant.now().minus(Duration.ofHours(24)));
            notice = smsGuard.check(ip, vr.smsSent(), toPhone);
            if (notice == null) {
                String code = newCode(vr);
                if (sms.send(vr.getPhone(), smsText(code))) {
                    smsGuard.record(ip);
                    vr.setChannel(CHANNEL_SMS);
                    vr.setSmsCount(vr.smsSent() + 1);
                    return toResult(vr, true, null, null);
                }
                clearCode(vr);   // yetib bormagan kod hech kimda yo'q — ishlatilmasin
                notice = "SMS yuborib bo'lmadi. Kodni Telegram bot orqali oling.";
            }
        }
        String devCode = null;
        boolean sent = false;
        TelegramLink link = linkRepository.findById(vr.getPhone()).orElse(null);
        if (link != null || !telegram.enabled()) {
            devCode = issueAndSend(vr, link == null ? null : link.getChatId());
            sent = link != null && telegram.enabled();
        }
        return toResult(vr, sent, devCode, notice);
    }

    String smsText(String code) {
        String t = env.getProperty("SMS_TEMPLATE");
        if (t == null || t.isBlank() || !t.contains("{code}")) t = DEFAULT_SMS_TEMPLATE;
        return t.replace("{code}", code);
    }

    /** Noto'g'ri urinishlar soni xato bo'lsa ham saqlanishi kerak — shuning uchun alohida tranzaksiya, rollback yo'q. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(String requestId, String code) {
        VerificationRequest vr = getActive(requestId);
        if (vr.isVerified()) return;
        if (vr.getCodeHash() == null) {
            throw ApiException.badRequest("Avval tasdiqlash kodini oling");
        }
        if (vr.getAttempts() >= props.verification().maxAttempts()) {
            throw ApiException.badRequest("Urinishlar soni tugadi. Kodni qayta so'rang");
        }
        if (vr.getCodeExpiresAt().isBefore(Instant.now())) {
            throw ApiException.badRequest("Kod muddati tugagan. Kodni qayta so'rang");
        }
        vr.setAttempts(vr.getAttempts() + 1);
        if (code == null || !hash(vr.getId(), code.trim()).equals(vr.getCodeHash())) {
            int left = props.verification().maxAttempts() - vr.getAttempts();
            throw ApiException.badRequest("Kod noto'g'ri. Qolgan urinishlar: " + left);
        }
        vr.setVerified(true);
        vr.setVerifiedAt(Instant.now());
    }

    /** Tasdiqlangan so'rovni bir martalik ishlatadi va telefon raqamni qaytaradi. */
    @Transactional
    public VerificationRequest consume(String requestId, VerificationPurpose purpose) {
        VerificationRequest vr = repository.findById(requestId)
                .orElseThrow(() -> ApiException.badRequest("Tasdiqlash so'rovi topilmadi"));
        if (vr.getPurpose() != purpose) throw ApiException.badRequest("Tasdiqlash so'rovi noto'g'ri");
        if (!vr.isVerified()) throw ApiException.badRequest("Telefon raqam tasdiqlanmagan");
        if (vr.isConsumed()) throw ApiException.badRequest("Bu tasdiqlash allaqachon ishlatilgan");
        if (vr.getVerifiedAt().plus(VERIFIED_WINDOW).isBefore(Instant.now())) {
            throw ApiException.badRequest("Tasdiqlash muddati o'tgan. Qaytadan boshlang");
        }
        vr.setConsumed(true);
        return vr;
    }

    /**
     * Bot foydalanuvchi kontaktini qabul qilganda chaqiriladi.
     * @return kod yuborilgan so'rovlar soni
     */
    @Transactional
    public int onContactLinked(String phone, long chatId, Long tgUserId, String username) {
        TelegramLink link = linkRepository.findById(phone).orElseGet(TelegramLink::new);
        link.setPhone(phone);
        link.setChatId(chatId);
        link.setTelegramUserId(tgUserId);
        link.setUsername(username);
        link.setLinkedAt(Instant.now());
        linkRepository.save(link);

        var pending = repository.findByPhoneAndConsumedFalseAndVerifiedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
                phone, Instant.now().minus(Duration.ofMinutes(30)));
        if (pending.isEmpty()) return 0;
        issueAndSend(pending.get(0), chatId); // eng oxirgi so'rovga kod
        return 1;
    }

    /** /start TOKEN — token bo'yicha so'rovni topish. */
    @Transactional(readOnly = true)
    public VerificationRequest findByToken(String token) {
        return repository.findByLinkToken(token).orElse(null);
    }

    /** Botdagi "Kod olish" tugmasi: shu chatga ulangan raqam uchun ochiq so'rov bo'lsa kodni qayta yuboradi. */
    @Transactional
    public boolean resendForChat(long chatId) {
        for (TelegramLink link : linkRepository.findByChatId(chatId)) {
            var pending = repository.findByPhoneAndConsumedFalseAndVerifiedFalseAndCreatedAtAfterOrderByCreatedAtDesc(
                    link.getPhone(), Instant.now().minus(Duration.ofMinutes(30)));
            if (!pending.isEmpty()) {
                issueAndSend(pending.get(0), chatId);
                return true;
            }
        }
        return false;
    }

    // ---- ichki ----

    /** Yangi 6 xonali kod: bazada faqat izi (hash) saqlanadi, eski kod bekor bo'ladi */
    private String newCode(VerificationRequest vr) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        vr.setCodeHash(hash(vr.getId(), code));
        vr.setCodeSentAt(Instant.now());
        vr.setCodeExpiresAt(Instant.now().plus(Duration.ofMinutes(props.verification().codeTtlMinutes())));
        vr.setAttempts(0);
        return code;
    }

    private static void clearCode(VerificationRequest vr) {
        vr.setCodeHash(null);
        vr.setCodeSentAt(null);
        vr.setCodeExpiresAt(null);
    }

    private String issueAndSend(VerificationRequest vr, Long chatId) {
        String code = newCode(vr);
        if (chatId != null) {
            vr.setChannel(CHANNEL_TELEGRAM);
            String text = "🔐 <b>YukGo</b> — " + vr.getPurpose().label() + " uchun tasdiqlash kodi:\n\n"
                    + "<code>" + code + "</code>\n\n"
                    + "Kod " + props.verification().codeTtlMinutes() + " daqiqa amal qiladi. "
                    + "Uni hech kimga bermang!";
            telegram.sendNow(chatId, text, TelegramClient.mainMenu());
        }
        if (!telegram.enabled()) {
            log.info("[DEV] {} uchun tasdiqlash kodi: {}", vr.getPhone(), code);
        }
        return props.devMode() && !telegram.enabled() ? code : null;
    }

    private VerificationRequest getActive(String requestId) {
        VerificationRequest vr = repository.findById(requestId)
                .orElseThrow(() -> ApiException.badRequest("Tasdiqlash so'rovi topilmadi"));
        if (vr.isConsumed()) throw ApiException.badRequest("Bu so'rov allaqachon ishlatilgan");
        if (vr.getCreatedAt().plus(Duration.ofMinutes(30)).isBefore(Instant.now())) {
            throw ApiException.badRequest("So'rov muddati tugagan. Qaytadan boshlang");
        }
        return vr;
    }

    private StartResult toResult(VerificationRequest vr, boolean sent, String devCode, String notice) {
        return new StartResult(vr.getId(), sent, telegram.deepLink(vr.getLinkToken()), telegram.botUsername(),
                vr.getCodeExpiresAt(), devCode, sent ? vr.getChannel() : null, notice);
    }

    private static String randomToken() {
        byte[] b = new byte[12];
        RANDOM.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String hash(String salt, String code) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest((salt + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
