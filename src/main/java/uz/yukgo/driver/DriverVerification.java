package uz.yukgo.driver;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * Haydovchining "Tekshirilgan haydovchi" belgisi uchun arizasi.
 * Hujjat rasmlari alohida jadvalda (DriverVerificationFile) va admin qaror qilgan zahoti o'chiriladi —
 * bu yerda faqat natija qoladi.
 */
@Entity
@Table(name = "driver_verifications", indexes = @Index(name = "idx_dv_user", columnList = "userId"))
@Getter
@Setter
@NoArgsConstructor
public class DriverVerification {
    public static final String PENDING = "PENDING";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long userId;

    /** PENDING — ko'rib chiqilmoqda, APPROVED — tasdiqlandi, REJECTED — rad etildi */
    @Column(nullable = false, length = 12)
    private String status = PENDING;

    /** Ariza paytidagi davlat raqami: keyin o'zgartirilsa belgi olib tashlanadi */
    @Column(length = 20)
    private String plateNumber;

    /** Ariza paytidagi mashina: "Isuzu NPR, oq" */
    @Column(length = 160)
    private String vehicleInfo;

    @Column(nullable = false)
    private Instant submittedAt = Instant.now();

    private Instant decidedAt;

    @Column(length = 160)
    private String decidedBy;

    @Column(length = 300)
    private String rejectReason;
}
