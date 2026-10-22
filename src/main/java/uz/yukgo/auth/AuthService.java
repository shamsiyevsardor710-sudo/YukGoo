package uz.yukgo.auth;

import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import uz.yukgo.auth.AuthDtos.*;
import uz.yukgo.common.ApiException;
import uz.yukgo.growth.GrowthService;
import uz.yukgo.common.Phones;
import uz.yukgo.location.District;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.security.JwtService;
import uz.yukgo.telegram.TelegramLinkRepository;
import uz.yukgo.user.User;
import uz.yukgo.user.UserDtos.MeDto;
import uz.yukgo.user.UserRepository;
import uz.yukgo.user.UserStatus;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final UserRepository userRepository;
    private final VerificationService verificationService;
    private final LocationService locationService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TelegramLinkRepository linkRepository;
    private final LoginAttemptGuard guard;
    private final TelegramAuthService telegramAuth;
    private final GrowthService growth;

    // ---------- Ro'yxatdan o'tish ----------

    @Transactional
    public VerificationService.StartResult registerStart(PhoneRequest req) {
        String phone = Phones.normalize(req.phone());
        if (userRepository.existsByPhone(phone)) {
            throw ApiException.conflict("Bu raqam allaqachon ro'yxatdan o'tgan. Tizimga kiring");
        }
        return verificationService.start(phone, VerificationPurpose.REGISTER, null);
    }

    @Transactional
    public AuthResponse registerComplete(RegisterCompleteRequest req) {
        VerificationRequest vr = verificationService.consume(req.requestId(), VerificationPurpose.REGISTER);
        if (userRepository.existsByPhone(vr.getPhone())) {
            throw ApiException.conflict("Bu raqam allaqachon ro'yxatdan o'tgan");
        }
        Region region = locationService.region(req.regionId());
        District district = locationService.district(req.districtId(), region);

        User u = new User();
        u.setPhone(vr.getPhone());
        u.setNames(req.firstName(), req.lastName());
        u.setPasswordHash(passwordEncoder.encode(req.password()));
        u.setRegion(region);
        u.setDistrict(district);
        u.setVillage(req.village().trim());
        u.setHouse(req.house().trim());
        u.setLastLoginAt(Instant.now());
        linkRepository.findById(vr.getPhone()).ifPresent(l -> u.setTelegramChatId(l.getChatId()));
        userRepository.save(u);
        telegramAuth.bindAfterPasswordProof(u, req.telegramInitData());
        // Qaysi kanal/hamkor/taklif orqali keldi (so'rovda bo'lmasa — botga qaysi havola bilan kirgani)
        growth.attribute(u, req.source(), req.ref());
        return new AuthResponse(jwtService.generate(u), MeDto.of(u));
    }

    // ---------- Kirish ----------

    @Transactional
    public AuthResponse login(LoginRequest req) {
        User user;
        if (req.phone() != null && !req.phone().isBlank()) {
            String phone = Phones.normalize(req.phone());
            guard.check(phone);
            user = userRepository.findByPhone(phone).orElse(null);
            if (user == null || !passwordEncoder.matches(req.password(), user.getPasswordHash())) {
                guard.fail(phone);
                throw ApiException.unauthorized("Telefon raqam yoki parol noto'g'ri");
            }
            guard.success(phone);
        } else {
            if (isBlank(req.firstName()) || isBlank(req.lastName())) {
                throw ApiException.badRequest("Ism va familiyani kiriting");
            }
            String key = (req.firstName().trim() + " " + req.lastName().trim()).toLowerCase();
            guard.check(key);
            List<User> candidates = userRepository.findByFirstNameLowerAndLastNameLower(
                    req.firstName().trim().toLowerCase(), req.lastName().trim().toLowerCase());
            List<User> matched = candidates.stream()
                    .filter(u -> passwordEncoder.matches(req.password(), u.getPasswordHash()))
                    .toList();
            if (matched.isEmpty()) {
                guard.fail(key);
                throw ApiException.unauthorized("Ism, familiya yoki parol noto'g'ri");
            }
            if (matched.size() > 1) {
                throw ApiException.badRequest("Bu ism-familiya bilan bir nechta hisob bor. Telefon raqam bilan kiring");
            }
            guard.success(key);
            user = matched.get(0);
        }
        if (user.getStatus() == UserStatus.BLOCKED) {
            throw ApiException.forbidden("Hisobingiz bloklangan" +
                    (user.getBlockReason() != null ? ": " + user.getBlockReason() : ""));
        }
        if (user.getStatus() == UserStatus.REMOVED) {
            throw ApiException.forbidden("Hisobingiz tizimdan chiqarilgan");
        }
        user.setLastLoginAt(Instant.now());
        telegramAuth.bindAfterPasswordProof(user, req.telegramInitData());
        return new AuthResponse(jwtService.generate(user), MeDto.of(user));
    }

    // ---------- Parolni tiklash ----------

    @Transactional
    public VerificationService.StartResult resetStart(PhoneRequest req) {
        String phone = Phones.normalize(req.phone());
        if (!userRepository.existsByPhone(phone)) {
            throw ApiException.notFound("Bu raqam bilan foydalanuvchi topilmadi");
        }
        return verificationService.start(phone, VerificationPurpose.RESET_PASSWORD, null);
    }

    @Transactional
    public AuthResponse resetComplete(ResetPasswordRequest req) {
        VerificationRequest vr = verificationService.consume(req.requestId(), VerificationPurpose.RESET_PASSWORD);
        User user = userRepository.findByPhone(vr.getPhone())
                .orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        if (!user.isActive()) throw ApiException.forbidden("Hisobingiz faol emas");
        user.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        user.revokeTokens();                           // boshqa qurilmalardagi (begona) kirishlar bekor bo'ladi
        telegramAuth.secureAfterPasswordChange(user);  // begona ulangan Telegram parolsiz kira olmasin
        telegramAuth.bindAfterPasswordProof(user, req.telegramInitData());
        return new AuthResponse(jwtService.generate(user), MeDto.of(user));
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
