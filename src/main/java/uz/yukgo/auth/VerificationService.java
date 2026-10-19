package uz.yukgo.auth;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.common.ApiException;
import uz.yukgo.config.AppProperties;
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
 * Telegram bot orqali tasdiqlash kodi yuborish.
 *
 * Telegram botlar telefon raqamga o'zi birinchi bo'lib yoza olmaydi, shuning uchun:
 *  1) foydalanuvchi saytda raqamini kiritadi;
 *  2) agar bu raqam botga avval ulangan bo'lsa — kod darhol botdan keladi;
 *  3) aks holda sayt t.me/BOT?start=TOKEN havolasini beradi, foydalanuvchi botda
 *     "Telefon raqamni yuborish" tugmasini bosadi, raqam mos kelsa bot kodni yuboradi.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VerificationService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Duration VERIFIED_WINDOW = Duration.ofMinutes(30);
    private static final Duration RESEND_COOLDOWN = Duration.ofSeconds(45);

    private final VerificationRequestRepository repository;
    private final TelegramLinkRepository linkRepository;
    private final TelegramClient telegram;
    private final AppProperties props;

    public record StartResult(String requestId, boolean codeSent, String botLink, String botUsername,
                              Instant expiresAt, String devCode) {}

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

        String devCode = null;
        boolean sent = false;
        TelegramLink link = linkRepository.findById(phone).orElse(null);
        if (link != null || !telegram.enabled()) {
            devCode = issueAndSend(vr, link == null ? null : link.getChatId());
            sent = link != null && telegram.enabled();
        }
        return toResult(vr, sent, devCode);
    }

    @Transactional
    public StartResult resend(String requestId) {
        VerificationRequest vr = getActive(requestId);
        if (vr.getCodeSentAt() != null && vr.getCodeSentAt().plus(RESEND_COOLDOWN).isAfter(Instant.now())) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Kodni qayta so'rashdan oldin biroz kuting");
        }
        TelegramLink link = linkRepository.findById(vr.getPhone()).orElse(null);
        String devCode = null;
        boolean sent = false;
        if (link != null || !telegram.enabled()) {
            devCode = issueAndSend(vr, link == null ? null : link.getChatId());
            sent = link != null && telegram.enabled();
        }
        return toResult(vr, sent, devCode);
    }

    /** So'rov holati (frontend bot orqali ulanishni kutayotganda so'rab turadi). */
    @Transactional(readOnly = true)
    public StartResult status(String requestId) {
        VerificationRequest vr = getActive(requestId);
        return toResult(vr, vr.getCodeSentAt() != null && telegram.enabled(), null);
    }

    /** Noto'g'ri urinishlar soni xato bo'lsa ham saqlanishi kerak — shuning uchun alohida tranzaksiya, rollback yo'q. */
    @Transactional(propagation = Propagation.REQUIRES_NEW, noRollbackFor = ApiException.class)
    public void verify(String requestId, String code) {
        VerificationRequest vr = getActive(requestId);
        if (vr.isVerified()) return;
        if (vr.getCodeHash() == null) {
            throw ApiException.badRequest("Avval Telegram bot orqali kod oling");
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

    private String issueAndSend(VerificationRequest vr, Long chatId) {
        String code = String.format("%06d", RANDOM.nextInt(1_000_000));
        vr.setCodeHash(hash(vr.getId(), code));
        vr.setCodeSentAt(Instant.now());
        vr.setCodeExpiresAt(Instant.now().plus(Duration.ofMinutes(props.verification().codeTtlMinutes())));
        vr.setAttempts(0);
        if (chatId != null) {
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

    private StartResult toResult(VerificationRequest vr, boolean sent, String devCode) {
        return new StartResult(vr.getId(), sent, telegram.deepLink(vr.getLinkToken()), telegram.botUsername(),
                vr.getCodeExpiresAt(), devCode);
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
