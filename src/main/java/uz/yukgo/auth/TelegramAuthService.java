package uz.yukgo.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.auth.AuthDtos.AuthResponse;
import uz.yukgo.common.ApiException;
import uz.yukgo.config.AppProperties;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.security.JwtService;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.telegram.TelegramInitData;
import uz.yukgo.telegram.TelegramLink;
import uz.yukgo.telegram.TelegramLinkRepository;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.MeDto;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;

import java.time.Duration;
import java.time.Instant;

/**
 * Telegram Mini App orqali kirish.
 *  • Hisob Telegramga ulangan bo'lsa — ilova ochilishi bilan avtomatik kiradi (parolsiz). Adminlar va ega bundan mustasno.
 *  • Telegramga ulash FAQAT parol (yoki Telegram kodi) shu so'rovning o'zida isbotlanganda bo'ladi: kirish,
 *    ro'yxatdan o'tish va parolni tiklash. Faqat token (JWT) bilan ulab bo'lmaydi — o'g'irlangan token yoki
 *    bir telefondagi boshqa Telegram hisobi orqali doimiy parolsiz kirish yo'li ochilmaydi.
 *  • Hisob boshqa Telegramga ko'chsa — eski Telegramga ogohlantirish boradi.
 * Telegram foydalanuvchi ID si shaxsiy chat ID si bilan bir xil, shuning uchun telegramChatId maydoni ishlatiladi.
 */
@Service
@RequiredArgsConstructor
public class TelegramAuthService {
    /** Shundan eski initData qabul qilinmaydi (ilova har ochilganda Telegram yangisini beradi) */
    private static final Duration MAX_AGE = Duration.ofHours(6);

    private final AppProperties props;
    private final UserRepository userRepository;
    private final JwtService jwtService;
    private final CurrentUser currentUser;
    private final TelegramClient telegram;
    private final TelegramLinkRepository linkRepository;

    private TelegramInitData.Verified verify(String initData) {
        return TelegramInitData.verify(initData, props.telegram().botToken(), MAX_AGE, Instant.now());
    }

    /** Mini App ochilganda avtomatik kirish. Ulanmagan bo'lsa 404 — frontend parol bilan kirish sahifasini ko'rsatadi. */
    @Transactional
    public AuthResponse login(String initData) {
        TelegramInitData.Verified tg = verify(initData);
        User user = userRepository.findFirstByTelegramChatIdOrderByIdDesc(tg.userId())
                .orElseThrow(() -> ApiException.notFound(
                        "Bu Telegram hisobi YukGo'ga hali ulanmagan. Bir marta parol bilan kiring yoki ro'yxatdan o'ting"));
        if (user.getRole().isStaff()) {
            throw ApiException.notFound("Adminlar va ega xavfsizlik uchun har safar parol bilan kiradi");
        }
        if (Boolean.FALSE.equals(user.getTelegramAutoLogin())) {
            throw ApiException.notFound("Siz Telegram ichida hisobdan chiqqansiz. Qayta kirish uchun parolingizni kiriting");
        }
        if (user.getStatus() == UserStatus.BLOCKED) {
            throw ApiException.forbidden("Hisobingiz bloklangan" + (user.getBlockReason() != null ? ": " + user.getBlockReason() : ""));
        }
        if (user.getStatus() == UserStatus.REMOVED) {
            throw ApiException.forbidden("Hisobingiz tizimdan chiqarilgan");
        }
        user.setLastLoginAt(Instant.now());
        return new AuthResponse(jwtService.generate(user), MeDto.of(user));
    }

    /**
     * Shu so'rovda paroli (yoki Telegram kodi) isbotlangan hisobni Mini App ochilgan Telegramga ulaydi.
     * Faqat AuthService (kirish, ro'yxatdan o'tish, parolni tiklash) ichidan, uning tranzaksiyasida chaqiriladi.
     * initData yo'q, noto'g'ri yoki eskirgan bo'lsa — jim o'tkazib yuboriladi: kirishning o'zi to'xtamasin.
     */
    public void bindAfterPasswordProof(User user, String initData) {
        if (initData == null || initData.isBlank()) return;
        TelegramInitData.Verified tg;
        try {
            tg = verify(initData);
        } catch (ApiException e) {
            return;
        }
        Long previous = user.getTelegramChatId();
        user.setTelegramAutoLogin(true);   // parol bilan kirdi — keyingi safar Mini App o'zi kiradi
        if (previous != null && previous == tg.userId()) return;
        attach(user, tg.userId());
        if (previous != null) {
            telegram.send(previous, "⚠️ YukGo hisobingiz (" + TelegramClient.esc(user.getFullName()) + ") boshqa Telegram hisobiga ulandi"
                    + (tg.username() != null ? " (@" + TelegramClient.esc(tg.username()) + ")" : "") + ".\n"
                    + "Bu siz bo'lmasangiz, parolingizni darhol o'zgartiring (Profil → Parolni o'zgartirish yoki "
                    + "«Parolni unutdingizmi?»): boshqa qurilmalardagi kirishlar bekor bo'ladi va begona Telegram parolsiz kira olmaydi.");
        }
    }

    /** Avtomatik kirish bir ma'noli bo'lishi uchun bitta Telegram — bitta hisob: shu Telegram boshqa hisoblardan uziladi */
    private void attach(User user, long chatId) {
        for (User other : userRepository.findByTelegramChatId(chatId)) {
            if (!other.getId().equals(user.getId())) other.setTelegramChatId(null);
        }
        user.setTelegramChatId(chatId);
    }

    /**
     * Parol almashganda (profilda o'zgartirildi yoki Telegram kodi bilan tiklandi): hisobga begona Telegram ulab olingan
     * bo'lsa, u endi parolsiz kira olmasin.
     *  • hisob Telegramdan uzilgan bo'lsa — tegilmaydi (foydalanuvchining o'z tanlovi);
     *  • raqami bot orqali tasdiqlangan Telegram boshqa bo'lsa — hisob o'shanga qaytadi;
     *  • har ikki holda Mini App'dagi avtomatik kirish o'chadi — u yerda bir marta yangi parol bilan kiriladi.
     * Ulanish (va xabarlar) saqlanadi: tasdiqlangan Telegram yo'q bo'lsa ham uzib yuborilmaydi.
     */
    public void secureAfterPasswordChange(User user) {
        Long current = user.getTelegramChatId();
        if (current == null) return;
        Long verified = linkRepository.findById(user.getPhone()).map(TelegramLink::getChatId).orElse(null);
        if (verified != null && !verified.equals(current)) attach(user, verified);
        user.setTelegramAutoLogin(false);
    }

    /** Profilda "Telegramdan uzish": Telegram xabarlari kelmaydi, Mini App avtomatik kirmaydi. */
    @Transactional
    public MeDto unlink() {
        User me = currentUser.get();
        me.setTelegramChatId(null);
        me.setTelegramAutoLogin(null);
        return MeDto.of(me);
    }

    /**
     * Mini App ichida "Chiqish": aks holda ilova keyingi ochilishda yana avtomatik kirib qo'yardi.
     * Faqat avtomatik kirish o'chadi — buyurtmalar haqidagi Telegram xabarlari kelaveradi.
     */
    @Transactional
    public void pauseAutoLogin() {
        currentUser.get().setTelegramAutoLogin(false);
    }
}
