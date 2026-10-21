package uz.yukgo.user;

import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import uz.yukgo.auth.VerificationPurpose;
import uz.yukgo.auth.VerificationRequest;
import uz.yukgo.auth.VerificationService;
import uz.yukgo.common.ApiException;
import uz.yukgo.common.PageResponse;
import uz.yukgo.common.Phones;
import uz.yukgo.file.FileStorageService;
import uz.yukgo.location.LocationService;
import uz.yukgo.location.Region;
import uz.yukgo.order.OrderRepository;
import uz.yukgo.order.OrderStatus;
import uz.yukgo.security.CurrentUser;
import uz.yukgo.telegram.TelegramLinkRepository;
import uz.yukgo.user.UserDtos.*;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;

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
        v.setBrand(dto.brand().trim());
        v.setModel(blankToNull(dto.model()));
        v.setPlateNumber(dto.plateNumber().trim().toUpperCase().replaceAll("\\s+", " "));
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

    @Transactional
    public void changePassword(ChangePasswordRequest req) {
        User u = currentUser.get();
        if (!passwordEncoder.matches(req.oldPassword(), u.getPasswordHash())) {
            throw ApiException.badRequest("Joriy parol noto'g'ri");
        }
        u.setPasswordHash(passwordEncoder.encode(req.newPassword()));
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

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
