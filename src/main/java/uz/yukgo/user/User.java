package uz.yukgo.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.DynamicUpdate;
import uz.yukgo.location.District;
import uz.yukgo.location.Region;
import uz.yukgo.order.OrderKind;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

/** DynamicUpdate: masalan, GPS yangilanishi (lastLat) bir vaqtda qo'yilgan blokni (status) qaytarib yubormasligi uchun. */
@DynamicUpdate
@Entity
@Table(name = "users", indexes = {
        @Index(name = "idx_users_name", columnList = "firstNameLower,lastNameLower")
})
@Getter
@Setter
@NoArgsConstructor
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 20)
    private String phone;

    @Column(nullable = false, length = 60)
    private String firstName;

    @Column(nullable = false, length = 60)
    private String lastName;

    /** Ism-familiya bilan kirish uchun kichik harfdagi nusxalar */
    @Column(nullable = false, length = 60)
    private String firstNameLower;

    @Column(nullable = false, length = 60)
    private String lastNameLower;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.USER;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private UserStatus status = UserStatus.ACTIVE;

    @Column(length = 300)
    private String blockReason;

    // ---- Manzil ----
    @ManyToOne(fetch = FetchType.LAZY)
    private Region region;

    @ManyToOne(fetch = FetchType.LAZY)
    private District district;

    /** Qishloq / mahalla (endi so'ralmaydi — eski hisoblarda qolgan; profil saqlanganda tozalanadi) */
    private String village;

    /** Ko'cha va uy raqami (endi so'ralmaydi) */
    private String house;

    // ---- Profil ----
    private String avatarUrl;

    @Column(length = 500)
    private String bio;

    @Embedded
    private Vehicle vehicle;

    // ---- Reyting va statistika ----
    private long ratingSum;
    private int ratingCount;
    /** Yuboruvchi sifatida yaratgan buyurtmalari */
    private int ordersCreated;
    /** Haydovchi sifatida muvaffaqiyatli yetkazgan buyurtmalari */
    private int deliveriesCompleted;
    private int cancelledAsDriver;

    // ---- Telegram ----
    private Long telegramChatId;

    /**
     * Mini App ochilganda parolsiz avtomatik kirish. Mini App ichida "Chiqish" bosilsa false bo'ladi (bildirishnomalar
     * esa kelaveradi), keyingi safar parol bilan kirilganda yana yoqiladi. null — eski hisoblar, yoqilgan deb hisoblanadi.
     * (Boolean — mavjud qatorlarga ustun qo'shilganda NOT NULL xatosi bo'lmasin.)
     */
    private Boolean telegramAutoLogin;

    /**
     * Kirish tokenlari versiyasi: parol almashganda yoki admin qilinganda oshadi — boshqa qurilmalardagi (va begona
     * qo'ldagi) eski tokenlar yaroqsiz bo'ladi. null — eski qatorlar, 0 deb hisoblanadi.
     */
    private Integer tokenVersion;

    // ---- Qayerdan keldi va kim taklif qildi (marketing hisobi uchun; barchasi ixtiyoriy) ----
    /** Ro'yxatdan o'tgan havola manbasi: kanal, hamkor, dala xodimi kodi (masalan "ch3", "agent_ali") */
    @Column(length = 40)
    private String signupSource;

    /** Taklif qilgan foydalanuvchi ID si (taklif havolasi orqali kelgan bo'lsa) */
    private Long referredById;

    /** Taklif bonusi berilgan vaqt (birinchi yetkazilgan buyurtmadan keyin bir marta) */
    private Instant referralRewardedAt;

    // ---- "Tekshirilgan haydovchi" belgisi (guvohnoma va texpasportni admin tekshirgan) ----
    private Boolean driverVerified;
    private Instant driverVerifiedAt;

    // ---- Haydovchi sozlamalari ----
    /** "Hozir bo'shman, buyurtma olaman" */
    private boolean available = true;

    /** Yangi buyurtmalar haqida Telegram orqali xabar olish */
    private boolean notifyNewOrders = true;

    /**
     * Qaysi e'lonlar haqida xabar oladi: "CARGO,PARCEL,PASSENGER" dan tanlanganlari. null — o'zi tanlamagan,
     * mashinasiga qarab ({@link OrderKind#defaultsFor}).
     */
    @Column(length = 40)
    private String notifyKinds;

    /** Haydovchi ishlaydigan viloyatlar (bo'sh bo'lsa — hammasi) */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "user_work_regions", joinColumns = @JoinColumn(name = "user_id"))
    @Column(name = "region_id")
    private Set<Long> workRegionIds = new HashSet<>();

    private Double lastLat;
    private Double lastLng;
    private Instant lastLocationAt;

    // ---- Foydalanish shartlari (Terms) ----
    /** Qabul qilingan shartlar versiyasi. null — hali qabul qilmagan (shu imkoniyatdan oldingi hisoblar) */
    private Integer termsVersion;
    private Instant termsAcceptedAt;

    private Instant createdAt = Instant.now();
    private Instant lastLoginAt;

    /** Joriy foydalanish shartlarini qabul qilganmi (shartlar yangilansa — qayta qabul qilishi kerak) */
    public boolean hasAcceptedTerms() {
        return termsVersion != null && termsVersion >= Terms.VERSION;
    }

    public void acceptTerms() {
        termsVersion = Terms.VERSION;
        termsAcceptedAt = Instant.now();
    }

    public void setNames(String first, String last) {
        this.firstName = first.trim();
        this.lastName = last.trim();
        this.firstNameLower = this.firstName.toLowerCase();
        this.lastNameLower = this.lastName.toLowerCase();
    }

    /** Haydovchi hujjatlari admin tomonidan tekshirilgan va mashinasi hali o'sha */
    public boolean isVerifiedDriver() {
        return Boolean.TRUE.equals(driverVerified) && isDriver();
    }

    public int currentTokenVersion() {
        return tokenVersion == null ? 0 : tokenVersion;
    }

    /** Hozirgacha berilgan barcha kirish tokenlarini bekor qiladi (yangisi yangi versiya bilan beriladi). */
    public void revokeTokens() {
        tokenVersion = currentTokenVersion() + 1;
    }

    public String getFullName() {
        return firstName + " " + lastName;
    }

    public double getRatingAvg() {
        return ratingCount == 0 ? 0 : Math.round((double) ratingSum / ratingCount * 10) / 10.0;
    }

    public boolean isDriver() {
        return vehicle != null && vehicle.isPresent();
    }

    /** Haydovchi qaysi turdagi e'lonlar haqida xabar oladi (o'zi tanlagani yoki mashinasiga qarab) */
    public Set<OrderKind> effectiveNotifyKinds() {
        Set<OrderKind> chosen = OrderKind.parse(notifyKinds);
        if (notifyKinds != null && !chosen.isEmpty()) return chosen;
        return OrderKind.defaultsFor(vehicle == null ? null : vehicle.getBodyType());
    }

    public boolean acceptsKind(OrderKind kind) {
        return effectiveNotifyKinds().contains(kind == null ? OrderKind.CARGO : kind);
    }

    public boolean isActive() {
        return status == UserStatus.ACTIVE;
    }
}
