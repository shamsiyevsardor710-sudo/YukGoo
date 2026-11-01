package uz.yukgo.order;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.user.BodyType;
import uz.yukgo.user.User;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Yetkazib berish buyurtmasi (yuk e'loni).
 * DynamicUpdate: UPDATE faqat o'zgargan ustunlarni yozadi — ko'rishlar soni yoki GPS yangilanishi
 * bir vaqtda bo'lgan "olish"/"bekor qilish" natijasini eski qiymat bilan ustidan yozib yubormaydi.
 */
@DynamicUpdate
@Entity(name = "Shipment") // "Order" HQL kalit so'zi bilan to'qnashmasligi uchun
@Table(name = "orders", indexes = {
        @Index(name = "idx_orders_status", columnList = "status,createdAt"),
        @Index(name = "idx_orders_from_to", columnList = "from_region_id,to_region_id")
})
@Getter
@Setter
@NoArgsConstructor
public class Order {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    private User sender;

    @ManyToOne(fetch = FetchType.LAZY)
    private User driver;

    /** E'lon turi: yuk, pochta yoki yo'lovchi. null — shu imkoniyatdan oldingi e'lonlar (yuk deb hisoblanadi) */
    @Enumerated(EnumType.STRING)
    @Column(length = 12)
    private OrderKind kind;

    /** Yo'lovchilar soni (faqat yo'lovchi e'lonida) */
    private Integer seats;

    // ---- Yuk ----
    @Column(nullable = false, length = 120)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private CargoType cargoType;

    private Double weightKg;
    private Double volumeM3;

    @Column(length = 1500)
    private String description;

    private String photoUrl;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private BodyType requiredBodyType;

    // ---- Qayerdan ----
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "from_region_id")
    private Region fromRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    private District fromDistrict;

    @Column(length = 250)
    private String fromAddress;
    private Double fromLat;
    private Double fromLng;

    // ---- Qayerga ----
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "to_region_id")
    private Region toRegion;

    @ManyToOne(fetch = FetchType.LAZY)
    private District toDistrict;

    @Column(length = 250)
    private String toAddress;
    private Double toLat;
    private Double toLng;

    /** Yandex marshrut bo'yicha masofa va vaqt (frontend hisoblaydi) */
    private Double distanceKm;
    private Integer durationMin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private DeliveryScope scope;

    // ---- Shartlar ----
    private LocalDate pickupDate;

    /** Yuboruvchi taklif qilgan narx (null — "kelishiladi", faqat takliflar orqali) */
    private Long price;

    /** Kelishilgan yakuniy narx */
    private Long finalPrice;

    /** Haydovchidan yechilgan komissiya (bekor qilinganda qaytarish uchun); null — yechilmagan */
    private Long commission;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private PaymentMethod paymentMethod = PaymentMethod.CASH;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private Payer payer = Payer.SENDER;

    private boolean urgent;

    // ---- Qabul qiluvchi ----
    @Column(length = 120)
    private String receiverName;

    @Column(length = 20)
    private String receiverPhone;

    /** Topshirish kodi: haydovchi faqat shu kodni kiritib "yetkazildi" qila oladi */
    @Column(length = 6)
    private String deliveryCode;

    // ---- Holat ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 15)
    private OrderStatus status = OrderStatus.NEW;

    private int offersCount;
    private int viewsCount;

    private Instant createdAt = Instant.now();
    private Instant acceptedAt;
    private Instant pickedUpAt;
    private Instant deliveredAt;
    private Instant cancelledAt;

    @Column(length = 300)
    private String cancelReason;

    // ---- Kuzatuv: haydovchining oxirgi joylashuvi ----
    private Double driverLat;
    private Double driverLng;
    private Instant driverLocationAt;

    public long effectivePrice() {
        return finalPrice != null ? finalPrice : (price != null ? price : 0);
    }

    /** Eski e'lonlarda tur yo'q — ular yuk */
    public OrderKind getKind() {
        return kind == null ? OrderKind.CARGO : kind;
    }

    public boolean isPassenger() {
        return getKind() == OrderKind.PASSENGER;
    }
}
