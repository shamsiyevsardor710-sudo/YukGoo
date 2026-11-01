package uz.yukgo.user;

import jakarta.validation.constraints.*;
import uz.yukgo.location.LocationService.DistrictDto;
import uz.yukgo.location.LocationService.RegionDto;
import uz.yukgo.order.OrderKind;

import java.time.Instant;
import java.util.Set;

public final class UserDtos {
    private UserDtos() {}

    /**
     * Mashina: rusumi (masalan «Chevrolet Cobalt», «Isuzu NPR»), davlat raqami, kuzov turi, ixtiyoriy rangi va rasmi.
     * Modeli, yili, yuk ko'tarishi va hajmi endi so'ralmaydi — eski ilova versiyalari yuborsa ham qabul qilinadi.
     * Javobda kuzov turi doim hozirgi 6 turdan biri ({@link BodyType#canonical()}).
     */
    public record VehicleDto(
            @NotBlank(message = "Mashinangizni yozing, masalan: Chevrolet Cobalt") @Size(max = 60, message = "Mashina nomi juda uzun") String brand,
            @Size(max = 40) String model,
            @NotBlank(message = "Davlat raqamini kiriting") @Size(max = 15, message = "Davlat raqami juda uzun") String plateNumber,
            @Size(max = 30) String color,
            Integer year,
            @NotNull(message = "Mashina turini tanlang") BodyType bodyType,
            Integer capacityKg,
            Double volumeM3,
            String photoUrl) {
        public static VehicleDto of(Vehicle v) {
            if (v == null || !v.isPresent()) return null;
            return new VehicleDto(v.getBrand(), v.getModel(), v.getPlateNumber(), v.getColor(), v.getYear(),
                    BodyType.canonical(v.getBodyType()), v.getCapacityKg(), v.getVolumeM3(), v.getPhotoUrl());
        }
    }

    /**
     * Hammaga ko'rinadigan profil (phone — null bo'lishi mumkin: yashirilgan).
     * blockedByMe — faqat ochiq profil sahifasida: ko'rayotgan odam uni bloklaganmi (ro'yxatlarda va o'zi uchun null).
     */
    public record PublicUserDto(
            Long id, String firstName, String lastName, String fullName, String phone, String avatarUrl,
            String bio, Role role, boolean driver, boolean available, VehicleDto vehicle,
            double rating, int ratingCount, int ordersCreated, int deliveriesCompleted,
            RegionDto region, DistrictDto district, Instant memberSince, boolean telegramLinked, boolean verified,
            Boolean blockedByMe) {
        public static PublicUserDto of(User u) {
            return of(u, true);
        }

        /** @param withPhone false — telefon raqami javobga qo'shilmaydi */
        public static PublicUserDto of(User u, boolean withPhone) {
            return of(u, withPhone, null);
        }

        public static PublicUserDto of(User u, boolean withPhone, Boolean blockedByMe) {
            return new PublicUserDto(u.getId(), u.getFirstName(), u.getLastName(), u.getFullName(), withPhone ? u.getPhone() : null,
                    u.getAvatarUrl(), u.getBio(), u.getRole(), u.isDriver(), u.isAvailable(),
                    VehicleDto.of(u.getVehicle()), u.getRatingAvg(), u.getRatingCount(), u.getOrdersCreated(),
                    u.getDeliveriesCompleted(), RegionDto.of(u.getRegion()), DistrictDto.of(u.getDistrict()),
                    u.getCreatedAt(), u.getTelegramChatId() != null, u.isVerifiedDriver(), blockedByMe);
        }
    }

    /** Men bloklagan foydalanuvchi (Profil → Bloklanganlar) */
    public record BlockedUserDto(Long id, String fullName, String avatarUrl, Instant blockedAt) {
        public static BlockedUserDto of(User u, Instant blockedAt) {
            return new BlockedUserDto(u.getId(), u.getFullName(), u.getAvatarUrl(), blockedAt);
        }
    }

    /** Ro'yxatlar uchun qisqa ko'rinish (phone — null bo'lishi mumkin: yashirilgan) */
    public record UserBrief(Long id, String fullName, String phone, String avatarUrl, double rating,
                            int ratingCount, int deliveriesCompleted, boolean driver, VehicleDto vehicle,
                            boolean verified) {
        public static UserBrief of(User u) {
            return of(u, true);
        }

        /** @param withPhone false — telefon raqami javobga qo'shilmaydi (masalan, buyurtma hali bu haydovchiga berilmagan) */
        public static UserBrief of(User u, boolean withPhone) {
            if (u == null) return null;
            return new UserBrief(u.getId(), u.getFullName(), withPhone ? u.getPhone() : null, u.getAvatarUrl(), u.getRatingAvg(),
                    u.getRatingCount(), u.getDeliveriesCompleted(), u.isDriver(), VehicleDto.of(u.getVehicle()),
                    u.isVerifiedDriver());
        }
    }

    /**
     * Foydalanuvchining o'z profili (qo'shimcha maxfiy maydonlar bilan).
     * termsAccepted — joriy foydalanish shartlarini qabul qilganmi (false bo'lsa ilova avval shartlarni ko'rsatadi).
     * notifyKinds — haydovchi qaysi e'lonlar (yuk, pochta, yo'lovchi) haqida xabar oladi; notifyKindsChosen — o'zi tanlaganmi.
     */
    public record MeDto(
            Long id, String firstName, String lastName, String fullName, String phone, String avatarUrl,
            String bio, Role role, boolean driver, boolean available, VehicleDto vehicle,
            double rating, int ratingCount, int ordersCreated, int deliveriesCompleted,
            RegionDto region, DistrictDto district, String village, String house,
            boolean telegramLinked, boolean notifyNewOrders, Set<Long> workRegionIds, Instant memberSince,
            boolean verified, boolean termsAccepted, Set<OrderKind> notifyKinds, boolean notifyKindsChosen) {
        public static MeDto of(User u) {
            return new MeDto(u.getId(), u.getFirstName(), u.getLastName(), u.getFullName(), u.getPhone(),
                    u.getAvatarUrl(), u.getBio(), u.getRole(), u.isDriver(), u.isAvailable(),
                    VehicleDto.of(u.getVehicle()), u.getRatingAvg(), u.getRatingCount(), u.getOrdersCreated(),
                    u.getDeliveriesCompleted(), RegionDto.of(u.getRegion()), DistrictDto.of(u.getDistrict()),
                    u.getVillage(), u.getHouse(), u.getTelegramChatId() != null, u.isNotifyNewOrders(),
                    Set.copyOf(u.getWorkRegionIds()), u.getCreatedAt(), u.isVerifiedDriver(), u.hasAcceptedTerms(),
                    u.effectiveNotifyKinds(), u.getNotifyKinds() != null && !OrderKind.parse(u.getNotifyKinds()).isEmpty());
        }
    }

    /** Qishloq/mahalla va uy endi so'ralmaydi (eski ilova versiyalari yuborsa ham qabul qilinadi, saqlanmaydi) */
    public record UpdateProfileRequest(
            @NotBlank(message = "Ismni kiriting") @Size(min = 2, max = 60, message = "Ism 2-60 belgi bo'lsin") String firstName,
            @NotBlank(message = "Familiyani kiriting") @Size(min = 2, max = 60, message = "Familiya 2-60 belgi bo'lsin") String lastName,
            @Size(max = 500) String bio,
            @NotNull(message = "Viloyatni tanlang") Long regionId,
            @NotNull(message = "Tumanni tanlang") Long districtId,
            @Size(max = 120) String village,
            @Size(max = 120) String house) {}

    /** notifyKinds — bo'sh to'plam yuborilsa: mashinasiga qarab (standart) */
    public record SettingsRequest(Boolean available, Boolean notifyNewOrders, Set<Long> workRegionIds,
                                  Set<OrderKind> notifyKinds) {}

    public record ChangePasswordRequest(
            @NotBlank String oldPassword,
            @NotBlank @Size(min = 6, max = 64, message = "Parol kamida 6 belgi bo'lsin") String newPassword) {}

    public record ChangePhoneStart(@NotBlank(message = "Yangi raqamni kiriting") String newPhone) {}

    public record ChangePhoneConfirm(@NotBlank String requestId, @NotBlank String code) {}

    public record LocationUpdate(@NotNull Double lat, @NotNull Double lng) {}

    public record DeleteAccountRequest(@NotBlank(message = "Parolni kiriting") String password) {}
}
