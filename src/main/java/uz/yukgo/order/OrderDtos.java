package uz.yukgo.order;

import jakarta.validation.constraints.*;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.UserDtos.UserBrief;

import java.time.Instant;
import java.time.LocalDate;

public final class OrderDtos {
    private OrderDtos() {}

    public record PlaceInput(
            @NotNull(message = "Viloyatni tanlang") Long regionId,
            Long districtId,
            @Size(max = 250) String address,
            @DecimalMin("-90") @DecimalMax("90") Double lat,
            @DecimalMin("-180") @DecimalMax("180") Double lng) {}

    /**
     * Yangi e'lon. kind: CARGO (yuk, standart), PARCEL (pochta) yoki PASSENGER (yo'lovchi).
     * Yo'lovchi uchun faqat qayerdan, qayerga, qachon va necha kishi (seats) kerak — sarlavha o'zi tuziladi.
     * Qabul qiluvchi ko'rsatilmasa — yuboruvchining o'zi. To'lov usuli va kim to'lashi endi so'ralmaydi (naqd, yuboruvchi).
     */
    public record CreateOrderRequest(
            @Size(max = 120, message = "Sarlavha juda uzun") String title,
            CargoType cargoType,
            @DecimalMin(value = "0.1", message = "Og'irlik noto'g'ri") @DecimalMax(value = "100000", message = "Og'irlik noto'g'ri") Double weightKg,
            @DecimalMin(value = "0.0") @DecimalMax("500") Double volumeM3,
            @Size(max = 1500) String description,
            BodyType requiredBodyType,
            @NotNull(message = "Qayerdan — manzilni kiriting") @jakarta.validation.Valid PlaceInput from,
            @NotNull(message = "Qayerga — manzilni kiriting") @jakarta.validation.Valid PlaceInput to,
            @DecimalMin("0") Double distanceKm,
            @Min(0) Integer durationMin,
            LocalDate pickupDate,
            @Min(value = 1000, message = "Narx kamida 1 000 so'm") @Max(value = 1_000_000_000L, message = "Narx juda katta") Long price,
            PaymentMethod paymentMethod,
            Payer payer,
            boolean urgent,
            @Size(max = 120) String receiverName,
            @Size(max = 30) String receiverPhone,
            OrderKind kind,
            @Min(value = 1, message = "Kamida 1 kishi") @Max(value = 30, message = "Ko'pi bilan 30 kishi") Integer seats) {}

    public record PlaceDto(Long regionId, String regionName, Long districtId, String districtName,
                           String address, Double lat, Double lng) {}

    /** Bitta DTO: kim ko'rayotganiga qarab maxfiy maydonlar to'ldiriladi yoki null qoladi. */
    public record OrderDto(
            Long id, String title, CargoType cargoType, Double weightKg, Double volumeM3, String description,
            String photoUrl, BodyType requiredBodyType, PlaceDto from, PlaceDto to,
            Double distanceKm, Integer durationMin, DeliveryScope scope, LocalDate pickupDate,
            Long price, Long finalPrice, PaymentMethod paymentMethod, Payer payer, boolean urgent,
            OrderStatus status, UserBrief sender, UserBrief driver,
            String receiverName, String receiverPhone, String deliveryCode,
            int offersCount, int viewsCount,
            Instant createdAt, Instant acceptedAt, Instant pickedUpAt, Instant deliveredAt,
            Instant cancelledAt, String cancelReason,
            /** SENDER | DRIVER | STAFF | GUEST */
            String viewerRole,
            OfferDto myOffer,
            Boolean reviewedByMe,
            Double driverLat, Double driverLng, Instant driverLocationAt,
            /** Haydovchidan yechilgan komissiya — faqat shu haydovchi va adminlarga ko'rinadi */
            Long commission,
            /**
             * Faqat buyurtma sahifasida, tomonlar uchun: "ME" — men ikkinchi tomonni bloklaganman, "CLOSED" — yozishma
             * boshqa sababdan yopiq (kim bloklagani oshkor qilinmaydi), null — blok yo'q
             */
            String block,
            /** CARGO | PARCEL | PASSENGER */
            OrderKind kind,
            /** Yo'lovchilar soni (yo'lovchi e'lonida) */
            Integer seats) {}

    public record OfferRequest(
            @NotNull(message = "Narxni kiriting") @Min(value = 1000, message = "Narx kamida 1 000 so'm") Long price,
            @Size(max = 500) String comment,
            @Min(0) @Max(720) Integer pickupInHours) {}

    public record OfferDto(Long id, Long orderId, UserBrief driver, Long price, String comment,
                           Integer pickupInHours, OfferStatus status, Instant createdAt) {
        public static OfferDto of(Offer f) {
            if (f == null) return null;
            return new OfferDto(f.getId(), f.getOrder().getId(), UserBrief.of(f.getDriver()), f.getPrice(),
                    f.getComment(), f.getPickupInHours(), f.getStatus(), f.getCreatedAt());
        }
    }

    /** Yuk va pochtada — qabul qiluvchidagi 4 xonali kod; yo'lovchi e'lonida kod kerak emas */
    public record DeliverRequest(@Size(max = 10) String code) {}

    public record CancelRequest(@Size(max = 300) String reason) {}

    public record TrackRequest(
            @NotNull @DecimalMin("-90") @DecimalMax("90") Double lat,
            @NotNull @DecimalMin("-180") @DecimalMax("180") Double lng,
            Double speedKmh, Double heading) {}

    public record TrackPointDto(double lat, double lng, Double speedKmh, Double heading, Instant at) {
        static TrackPointDto of(TrackPoint p) {
            return new TrackPointDto(p.getLat(), p.getLng(), p.getSpeedKmh(), p.getHeading(), p.getCreatedAt());
        }
    }

    public record TrackDto(TrackPointDto last, java.util.List<TrackPointDto> path, OrderStatus status) {}

    public record EventDto(OrderStatus status, String text, String actorName, Instant createdAt) {
        static EventDto of(OrderEvent e) {
            return new EventDto(e.getStatus(), e.getText(), e.getActorName(), e.getCreatedAt());
        }
    }

    public record MessageRequest(@NotBlank(message = "Xabar bo'sh") @Size(max = 1000) String text) {}

    public record MessageDto(Long id, Long authorId, String authorName, String authorAvatar, String text, Instant createdAt) {
        static MessageDto of(OrderMessage m) {
            return new MessageDto(m.getId(), m.getAuthor().getId(), m.getAuthor().getFullName(),
                    m.getAuthor().getAvatarUrl(), m.getText(), m.getCreatedAt());
        }
    }

    public record EstimateDto(long suggested, long min, long max, DeliveryScope scope) {}
}
