package uz.yukgo.user;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.admin.AdminLogRepository;
import uz.yukgo.auth.AuthDtos.AuthResponse;
import uz.yukgo.auth.LoginAttemptGuard;
import uz.yukgo.auth.TelegramAuthService;
import uz.yukgo.auth.VerificationPurpose;
import uz.yukgo.auth.VerificationRequest;
import uz.yukgo.auth.VerificationRequestRepository;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.common.Phones;
import uz.yukgo.driver.DriverVerificationService;
import uz.yukgo.file.FileStorageService;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.notification.Notification;
import uz.yukgo.notification.NotificationRepository;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderService;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.security.JwtService;
import uz.yukgo.telegram.TelegramClient;
import uz.yukgo.telegram.TelegramLinkRepository;
import uz.yukgo.trip.TripRepository;
import uz.yukgo.user.UserDtos.*;
import uz.yukgo.wallet.WalletService;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {
    private final UserRepository userRepository;
    private final CurrentUser currentUser;
    private final LocationService locationService;
    private final FileStorageService files;
    private final PasswordEncoder passwordEncoder;
    private final VerificationService verificationService;
    private final TelegramLinkRepository linkRepository;
    private final OrderRepository orderRepository;
    private final OrderService orderService;
    private final WalletService wallet;
    private final TelegramClient telegram;
    private final LoginAttemptGuard guard;
    private final AdminLogRepository adminLogs;
    private final VerificationRequestRepository verifications;
    private final NotificationRepository notificationRepository;
    private final TripRepository tripRepository;
    private final JwtService jwtService;
    private final TelegramAuthService telegramAuth;
    private final DriverVerificationService driverVerification;

    /** O'chirilgan hisobning ism-familiyasi (buyurtmalar tarixida shunday ko'rinadi) */
    private static final String DELETED_FIRST = "O'chirilgan";
    private static final String DELETED_LAST = "foydalanuvchi";
    private static final String DELETED_NAME = DELETED_FIRST + " " + DELETED_LAST;

    /** Shu holatdagi umumiy buyurtma bo'lsa, ikki tomon bir-birining telefonini ko'radi */
    private static final List<OrderStatus> DEAL_STATUSES = List.of(OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT, OrderStatus.DELIVERED);

    @Transactional(readOnly = true)
    public MeDto me() {
        return MeDto.of(currentUser.get());
    }

    @Transactional(readOnly = true)
    public PublicUserDto publicProfile(Long id) {
        User u = userRepository.findById(id).orElseThrow(() -> ApiException.notFound("Foydalanuvchi topilmadi"));
        if (u.getStatus() == UserStatus.REMOVED) throw ApiException.notFound("Foydalanuvchi topilmadi");
        return PublicUserDto.of(u, canSeePhone(u));
    }

    /**
     * Ochiq profilda telefon kimga ko'rinadi:
     *  • haydovchilarniki — hammaga (haydovchilar ro'yxati va reyslar aynan to'g'ridan-to'g'ri bog'lanish uchun);
     *  • qolganlarniki (yuboruvchilar) — faqat o'ziga, adminlarga va u bilan buyurtma bo'yicha bog'langan odamga.
     * Aks holda haydovchi yuboruvchi raqamini profildan olib, platformani chetlab kelishib olishi mumkin bo'lardi.
     */
    private boolean canSeePhone(User target) {
        if (target.isDriver()) return true;
        Long viewerId = currentUser.idOrNull();
        if (viewerId == null) return false;
        if (viewerId.equals(target.getId())) return true;
        User viewer = userRepository.findById(viewerId).orElse(null);
        if (viewer == null) return false;
        if (viewer.getRole().isStaff()) return true;
        return orderRepository.countDealsBetween(viewerId, target.getId(), DEAL_STATUSES) > 0;
    }

    @Transactional
    public MeDto updateProfile(UpdateProfileRequest req) {
        User u = currentUser.get();
        Region region = locationService.region(req.regionId());
        u.setNames(req.firstName(), req.lastName());
        u.setBio(blankToNull(req.bio()));
        u.setRegion(region);
        u.setDistrict(locationService.district(req.districtId(), region));
        u.setVillage(req.village().trim());
        u.setHouse(req.house().trim());
        return MeDto.of(u);
    }

    @Transactional
    public MeDto uploadAvatar(MultipartFile file) {
        User u = currentUser.get();
        String url = files.storeImage(file, "avatars");
        files.delete(u.getAvatarUrl());
        u.setAvatarUrl(url);
        return MeDto.of(u);
    }

    @Transactional
    public MeDto deleteAvatar() {
        User u = currentUser.get();
        files.delete(u.getAvatarUrl());
        u.setAvatarUrl(null);
        return MeDto.of(u);
    }

    @Transactional
    public MeDto updateVehicle(VehicleDto dto) {
        User u = currentUser.get();
        Vehicle v = u.getVehicle() == null ? new Vehicle() : u.getVehicle();
        String oldPlate = v.getPlateNumber();
        v.setBrand(dto.brand().trim());
        v.setModel(blankToNull(dto.model()));
        v.setPlateNumber(dto.plateNumber().trim().toUpperCase().replaceAll("\\s+", " "));
        // Boshqa mashina — "Tekshirilgan haydovchi" belgisi qayta tekshiruvgacha olinadi
        if (oldPlate != null && !Objects.equals(oldPlate, v.getPlateNumber())) driverVerification.vehicleChanged(u);
        v.setColor(blankToNull(dto.color()));
        v.setYear(dto.year());
        v.setBodyType(dto.bodyType());
        v.setCapacityKg(dto.capacityKg());
        v.setVolumeM3(dto.volumeM3());
        u.setVehicle(v);
        return MeDto.of(u);
    }

    @Transactional
    public MeDto uploadVehiclePhoto(MultipartFile file) {
        User u = currentUser.get();
        if (!u.isDriver()) throw ApiException.badRequest("Avval mashina ma'lumotlarini kiriting");
        String url = files.storeImage(file, "vehicles");
        files.delete(u.getVehicle().getPhotoUrl());
        u.getVehicle().setPhotoUrl(url);
        return MeDto.of(u);
    }

    @Transactional
    public MeDto removeVehicle() {
        User u = currentUser.get();
        if (u.getVehicle() != null) files.delete(u.getVehicle().getPhotoUrl());
        u.setVehicle(null);
        driverVerification.vehicleChanged(u);
        return MeDto.of(u);
    }

    @Transactional
    public MeDto updateSettings(SettingsRequest req) {
        User u = currentUser.get();
        if (req.available() != null) u.setAvailable(req.available());
        if (req.notifyNewOrders() != null) u.setNotifyNewOrders(req.notifyNewOrders());
        if (req.workRegionIds() != null) {
            u.getWorkRegionIds().clear();
            for (Long id : new HashSet<>(req.workRegionIds())) {
                locationService.region(id);
                u.getWorkRegionIds().add(id);
            }
        }
        return MeDto.of(u);
    }

    /**
     * Parolni o'zgartirish. Boshqa qurilmalardagi barcha kirishlar bekor bo'ladi (kimdir eski parol bilan kirgan bo'lsa
     * ham), joriy qurilma uchun yangi token qaytariladi. Begona ulangan Telegram parolsiz kira olmaydi.
     */
    @Transactional
    public AuthResponse changePassword(ChangePasswordRequest req) {
        User u = currentUser.get();
        String guardKey = "pwd:" + u.getId();
        guard.check(guardKey);
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            guard.fail(guardKey);
            throw ApiException.badRequest("Joriy parol noto'g'ri");
        }
        guard.success(guardKey);
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
        u.revokeTokens();
        telegramAuth.secureAfterPasswordChange(u);
        return new AuthResponse(jwtService.generate(u), MeDto.of(u));
    }

    @Transactional
    public VerificationService.StartResult changePhoneStart(ChangePhoneStart req) {
        User u = currentUser.get();
        String phone = Phones.normalize(req.newPhone());
        if (phone.equals(u.getPhone())) throw ApiException.badRequest("Bu sizning hozirgi raqamingiz");
        if (userRepository.existsByPhone(phone)) throw ApiException.conflict("Bu raqam boshqa hisobga biriktirilgan");
        return verificationService.start(phone, VerificationPurpose.CHANGE_PHONE, u.getId());
    }

    @Transactional
    public MeDto changePhoneConfirm(ChangePhoneConfirm req) {
        User u = currentUser.get();
        verificationService.verify(req.requestId(), req.code());
        VerificationRequest vr = verificationService.consume(req.requestId(), VerificationPurpose.CHANGE_PHONE);
        if (!u.getId().equals(vr.getUserId())) throw ApiException.forbidden("Ruxsat yo'q");
        if (userRepository.existsByPhone(vr.getPhone())) throw ApiException.conflict("Bu raqam boshqa hisobga biriktirilgan");
        u.setPhone(vr.getPhone());
        linkRepository.findById(vr.getPhone()).ifPresent(l -> u.setTelegramChatId(l.getChatId()));
        return MeDto.of(u);
    }

    @Transactional
    public void updateLocation(LocationUpdate loc) {
        User u = currentUser.get();
        u.setLastLat(loc.lat());
        u.setLastLng(loc.lng());
        u.setLastLocationAt(Instant.now());
    }

    @Transactional(readOnly = true)
    public PageResponse<PublicUserDto> drivers(Long regionId, BodyType bodyType, Integer minCapacity, int page) {
        return PageResponse.of(userRepository.searchDrivers(regionId, bodyType, minCapacity,
                PageRequest.of(page, 20)), PublicUserDto::of);
    }

    /**
     * Foydalanuvchi o'z hisobini o'chiradi (Google Play talabi). O'chiriladi: ism, telefon, manzil, rasmlar, mashina,
     * Telegram ulanishi, joylashuv va haydovchi sifatidagi GPS yo'llari, reys e'lonlari, bildirishnomalar, tasdiqlash
     * so'rovlari. Buyurtmalar, baholar va balans amallari tarixi ikkinchi tomon va hisob-kitob uchun
     * "O'chirilgan foydalanuvchi" nomi bilan qoladi. Telefon raqam bo'shaydi — shu raqam bilan qayta ro'yxatdan o'tish mumkin.
     */
    @Transactional
    public void deleteAccount(DeleteAccountRequest req) {
        // Qator bloklanadi: ikki marta bosilsa ikkinchisi birinchisini kutadi va eskirgan ma'lumot bilan ishlamaydi
        User u = userRepository.lockById(currentUser.id())
                .orElseThrow(() -> ApiException.unauthorized("Foydalanuvchi topilmadi"));
        if (u.getRole() == Role.OWNER) {
            throw ApiException.badRequest("Ega hisobini o'chirib bo'lmaydi. Avval egalikni boshqa adminga topshiring");
        }
        String guardKey = "del:" + u.getId();
        guard.check(guardKey);
        if (!passwordEncoder.matches(req.password(), u.getPasswordHash())) {
            guard.fail(guardKey);
            throw ApiException.badRequest("Parol noto'g'ri");
        }
        guard.success(guardKey);
        List<OrderStatus> active = List.of(OrderStatus.ACCEPTED, OrderStatus.IN_TRANSIT);
        if (orderRepository.countForUser(u.getId(), active) > 0) {
            throw ApiException.badRequest("Sizda haydovchiga berilgan yoki yo'ldagi buyurtma bor. Avval uni yakunlang yoki bekor qiling");
        }
        if (wallet.ownMoney(u.getId()) > 0) {
            throw ApiException.badRequest("Balansingizda mablag' bor. Hisobni o'chirishdan oldin uni qaytarib olish uchun admin bilan bog'laning");
        }
        orderService.closeForDeletedAccount(u);
        // Tekshiruv va bloklash orasida kimdir taklifini qabul qilgan bo'lsa — to'xtatamiz (hammasi bekor bo'ladi)
        if (orderRepository.countForUser(u.getId(), active) > 0) {
            throw ApiException.conflict("Taklifingiz hozirgina qabul qilindi. Avval shu buyurtmani yakunlang yoki undan voz keching");
        }

        Long chatId = u.getTelegramChatId();
        String oldPhone = u.getPhone();
        String oldName = u.getFullName();
        String avatar = u.getAvatarUrl();
        String vehiclePhoto = u.getVehicle() != null ? u.getVehicle().getPhotoUrl() : null;

        orderService.anonymizeHistory(u, oldName, DELETED_NAME);
        adminLogs.renameTarget(u.getId(), DELETED_NAME);
        verifications.deleteForAccount(oldPhone, u.getId());
        driverVerification.deleteAllFor(u.getId());
        wallet.forfeitBalance(u.getId(), "Hisob o'chirildi — bonus qoldig'i bekor qilindi");   // hujjat rasmlari (ko'rib chiqilmagan ariza bo'lsa)
        notificationRepository.deleteAllForUser(u.getId());
        // Boshqalarga kelgan bildirishnomalardagi telefon raqami ("Yuboruvchi: ..., +998...") olib tashlanadi
        for (Notification n : notificationRepository.findByBodyContaining(oldPhone)) {
            n.setBody(n.getBody().replace(oldPhone, "***"));
        }
        // Shu Telegram orqali avval tasdiqlangan boshqa (eski) raqamlar ham
        if (chatId != null) linkRepository.deleteAll(linkRepository.findByChatId(chatId));
        // Reys e'lonlari (izohlarida telefon va boshqa ma'lumot bo'lishi mumkin) — ularga boshqa jadval bog'lanmagan
        tripRepository.deleteAll(tripRepository.findByDriverIdOrderByDepartAtDesc(u.getId()));
        linkRepository.deleteById(oldPhone);

        u.setNames(DELETED_FIRST, DELETED_LAST);
        // Ism bilan kirishda o'chirilgan hisoblar umuman topilmasin (har biri uchun parol tekshirib serverni yuklamasin)
        u.setFirstNameLower("deleted-" + u.getId());
        u.setLastNameLower("deleted-" + u.getId());
        u.setPhone("del-" + u.getId());
        u.setPasswordHash(passwordEncoder.encode(UUID.randomUUID().toString()));
        u.setAvatarUrl(null);
        u.setBio(null);
        u.setVehicle(null);
        u.setRegion(null);
        u.setDistrict(null);
        u.setVillage(null);
        u.setHouse(null);
        u.setTelegramChatId(null);
        u.setTelegramAutoLogin(null);
        u.setDriverVerified(null);
        u.setDriverVerifiedAt(null);
        u.setLastLat(null);
        u.setLastLng(null);
        u.setLastLocationAt(null);
        u.setAvailable(false);
        u.setNotifyNewOrders(false);
        u.getWorkRegionIds().clear();
        if (u.getRole() == Role.ADMIN) u.setRole(Role.USER);
        u.setStatus(UserStatus.REMOVED);
        u.setBlockReason("Foydalanuvchi hisobini o'zi o'chirdi");
        u.revokeTokens();

        // Rasm fayllari va Telegram xabari — faqat o'chirish bazada muvaffaqiyatli yakunlangach
        // (o'rtada xato bo'lib hammasi bekor bo'lsa, rasmlar yo'qolmasin va "o'chirildi" xabari ketmasin)
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                files.delete(avatar);
                files.delete(vehiclePhoto);
                if (chatId != null) {
                    telegram.send(chatId, "🗑 YukGo hisobingiz o'chirildi. Shaxsiy ma'lumotlaringiz tizimdan olib tashlandi.\n"
                            + "Qaytmoqchi bo'lsangiz, istalgan vaqtda qayta ro'yxatdan o'tishingiz mumkin.");
                }
            }
        });
    }

    /** Hisobni foydalanuvchining o'zi o'chirganmi (shaxsiy ma'lumotlari yo'q — tiklab bo'lmaydi) */
    public static boolean isSelfDeleted(User u) {
        return u.getPhone() != null && u.getPhone().startsWith("del-");
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
